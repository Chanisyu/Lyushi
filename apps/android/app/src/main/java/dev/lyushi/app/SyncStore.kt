package dev.lyushi.app

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** 在框架的建库或升级事务内执行，不能递归调用 SQLiteOpenHelper 获取数据库。 */
internal fun initializeSync(db: SQLiteDatabase, migrate: Boolean) {
    db.execSQL("DROP INDEX IF EXISTS one_active")
    db.execSQL("CREATE INDEX IF NOT EXISTS active_records ON entities(json_extract(payload,'$.started')) WHERE kind='record' AND json_extract(payload,'$.deleted')=0 AND json_extract(payload,'$.ended') IS NULL")
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_heads(kind TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id,version))")
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_awaiting(version TEXT PRIMARY KEY,kind TEXT NOT NULL,id TEXT NOT NULL)")
    if (migrate) {
        db.execSQL("DELETE FROM outbox")
        for (kind in listOf("ledger", "record")) {
            val entities = db.rawQuery("SELECT payload FROM entities WHERE kind=? ORDER BY id", arrayOf(kind)).use { cursor -> buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) } }
            entities.forEach { entity ->
                val version = UUID.randomUUID().toString(); entity.put("version", version)
                db.update("entities", ContentValues().apply { put("payload", canonical(entity)) }, "kind=? AND id=?", arrayOf(kind, entity.getString("id")))
                db.insertOrThrow("outbox", null, ContentValues().apply { put("id", version); put("kind", kind); put("entity_id", entity.getString("id")); put("payload", canonical(JSONObject().put("parents", JSONArray()).put("entity", entity))) })
            }
        }
        val archived = db.rawQuery("SELECT payload FROM conflicts", null).use { cursor -> buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) } }
        archived.forEach { queueArchive(db,it) }
    }
    db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES('protocol','1')")
}

/** 保留的导入内容通过档案类型同步，历史未结束版本不会被当作新活动启动。 */
internal fun queueArchive(db: SQLiteDatabase, content: JSONObject) {
    val wrapped = JSONObject(content.toString()).put("archived", true)
    val hash = MessageDigest.getInstance("SHA-256").digest(canonical(wrapped).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val version = UUID.randomUUID().toString()
    val entity = JSONObject().put("id",hash).put("version",version).put("revision",1).put("deleted",false).put("content",wrapped)
    db.insertOrThrow("outbox",null,ContentValues().apply { put("id",version);put("kind","archive");put("entity_id",hash);put("payload",canonical(JSONObject().put("parents",JSONArray()).put("entity",entity))) })
}

/** 请求批次保持数据库原始操作身份；外部文件和网络均不能直接改写队列内容。 */
internal fun Store.syncRequest(): JSONObject {
    val result = JSONObject().put("serverId", "").put("cursor", 0)
    readableDatabase.rawQuery("SELECT key,value FROM sync_meta WHERE key IN ('serverId','cursor')", null).use { cursor -> while (cursor.moveToNext()) {
        val key = cursor.getString(0); result.put(key, if (key == "cursor") cursor.getString(1).toLong() else cursor.getString(1))
    } }
    val operations = JSONArray(); var bytes = 0
    readableDatabase.rawQuery("SELECT id,kind,entity_id,payload FROM outbox ORDER BY rowid LIMIT 50", null).use { cursor -> while (cursor.moveToNext()) {
        val payload = JSONObject(cursor.getString(3)); val operation = JSONObject().put("id", cursor.getString(0)).put("kind", cursor.getString(1)).put("entityId", cursor.getString(2))
            .put("parents", payload.getJSONArray("parents")).put("entity", payload.getJSONObject("entity"))
        val size = canonical(operation).toByteArray(Charsets.UTF_8).size + 1
        if (operations.length()>0 && bytes+size>1536*1024) break
        operations.put(operation); bytes+=size
    } }
    return result.put("operations", operations)
}
internal fun Store.pendingCount(): Long = DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM outbox", null)

/** 接受响应的事务边界：确认、分支缓存、界面实体与下载游标一起提交。 */
internal fun Store.acceptSync(response: JSONObject, sent: JSONArray): Boolean {
    val before = syncRequest()
    // 空闲轮询已经确认身份且没有变化时保持数据库只读，减少磁盘写入。
    if (sent.length()==0 && response.getJSONArray("ack").length()==0 && response.getJSONArray("changes").length()==0
        && !response.getBoolean("more") && before.getString("serverId").isNotEmpty()
        && before.getString("serverId")==response.getString("serverId") && before.get("cursor").toString()==response.get("cursor").toString()) return false
    return acceptSyncTransaction(response,sent)
}

private fun Store.acceptSyncTransaction(response: JSONObject, sent: JSONArray): Boolean = transaction {
    val before = syncRequest(); val server = response.getString("serverId")
    check(server.isNotEmpty() && (before.getString("serverId").isEmpty() || before.getString("serverId") == server)) { "服务器身份变化，已保护本机数据，请核对地址" }
    val expected = sent.objects().map { it.getString("id") }.toSet()
    val acks = response.getJSONArray("ack"); val acknowledged = (0 until acks.length()).map { acks.getString(it) }.toSet()
    check(expected == acknowledged && acks.length() == acknowledged.size) { "服务器未确认完整批次，本机队列保留" }
    val db = writableDatabase
    sent.objects().forEach { op ->
        db.insertWithOnConflict("sync_awaiting", null, ContentValues().apply { put("version", op.getString("id")); put("kind", op.getString("kind")); put("id", op.getString("entityId")) }, SQLiteDatabase.CONFLICT_IGNORE)
        db.delete("outbox", "id=?", arrayOf(op.getString("id")))
    }
    var cursor = before.getLong("cursor"); val touched = mutableSetOf<Pair<String,String>>()
    response.getJSONArray("changes").objects().forEach { change ->
        val kind = change.getString("kind"); val id = change.getString("id"); val heads = change.getJSONArray("heads").objects()
        check(kind in listOf("ledger", "record", "tag", "archive") && id.isNotEmpty() && heads.isNotEmpty() && change.getLong("seq") > cursor) { "同步变更无效" }
        cursor = change.getLong("seq"); touched.add(kind to id); db.delete("sync_heads", "kind=? AND id=?", arrayOf(kind,id))
        heads.forEach { entity ->
            check(entity.getString("id") == id && entity.getString("version").isNotEmpty() && entity.get("deleted") is Boolean) { "同步版本无效" }
            if (kind == "archive") {
                val content = entity.getJSONObject("content"); val originalKind = content.getString("kind"); val original = content.getJSONObject("entity")
                check(originalKind in listOf("ledger","record","tag") && original.getString("id").isNotEmpty()) { "同步档案类型无效" }
                // 归档标签与任务同样校验，防止导入内容经网络绕过字段和长度限制。
                if (originalKind == "record") validateRecord(original, true) else validateLedger(original)
                content.put("archived",true)
                db.insertWithOnConflict("conflicts",null,ContentValues().apply { put("id",id);put("kind",originalKind);put("entity_id",original.getString("id"));put("payload",canonical(content)) },SQLiteDatabase.CONFLICT_IGNORE)
            } else if (kind == "record") {
                validateRecord(entity, true)
                check(entity.get("task") is String && entity.getString("task").isNotBlank() && entity.getString("task").length <= 10000 && entity.getString("ledgerId").isNotEmpty()) { "同步事项无效" }
                check(entity.get("started") is Number && entity.getLong("started") >= 0 && entity.getLong("started") < 253402271999000L && entity.has("ended"))
                if (!entity.isNull("ended")) check(entity.get("ended") is Number && entity.getLong("ended") >= entity.getLong("started") && entity.getLong("ended") < 253402271999000L)
            } else validateLedger(entity)
            db.delete("sync_awaiting", "version=?", arrayOf(entity.getString("version")))
            db.insertOrThrow("sync_heads", null, ContentValues().apply { put("kind", kind); put("id", id); put("version", entity.getString("version")); put("payload", canonical(entity)) })
            if (heads.size > 1 && kind != "archive") {
                val payload = canonical(JSONObject().put("kind", kind).put("entity", entity).put("archived", true))
                val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                db.insertWithOnConflict("conflicts", null, ContentValues().apply { put("id", hash); put("kind", kind); put("entity_id", id); put("payload", payload) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }
    check(response.getLong("cursor") == cursor) { "同步游标不匹配" }
    var changed = false
    touched.forEach { (kind,id) ->
        val pending = DatabaseUtils.longForQuery(db, "SELECT (SELECT COUNT(*) FROM outbox WHERE kind=? AND entity_id=?) + (SELECT COUNT(*) FROM sync_awaiting WHERE kind=? AND id=?)", arrayOf(kind,id,kind,id)) > 0
        if (!pending) {
            val heads = db.rawQuery("SELECT payload FROM sync_heads WHERE kind=? AND id=? ORDER BY version", arrayOf(kind,id)).use { rows -> buildList { while (rows.moveToNext()) add(JSONObject(rows.getString(0))) } }
            val current = find(kind,id)
            fun priority(entity: JSONObject) = (if (kind == "tag" && entity.getBoolean("deleted")) 16 else 0) + (if (!entity.getBoolean("deleted")) 4 else 0) + (if (kind == "record" && entity.isNull("ended")) 8 else 0) + (if (entity.optString("version") == current?.optString("version")) 1 else 0)
            val chosen = heads.maxBy { priority(it) }
            if (canonical(chosen) != canonical(current)) { changed = true; db.insertWithOnConflict("entities", null, ContentValues().apply { put("kind",kind); put("id",id); put("payload",canonical(chosen)) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) } }
        }
    }
    // 删除账本后才到达的离线记录另存档案，保证仍可查看，不自动恢复整个账本。
    if (touched.isNotEmpty()) {
        val orphans = db.rawQuery("SELECT r.payload FROM entities r LEFT JOIN entities b ON b.kind='ledger' AND b.id=json_extract(r.payload,'$.ledgerId') WHERE r.kind='record' AND json_extract(r.payload,'$.deleted')=0 AND (b.id IS NULL OR json_extract(b.payload,'$.deleted')=1)",null).use { rows -> buildList { while (rows.moveToNext()) add(JSONObject(rows.getString(0))) } }
        orphans.forEach { entity ->
            val wrapped = JSONObject().put("kind","record").put("entity",entity).put("archived",true)
            val payload = canonical(wrapped); val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            db.insertWithOnConflict("conflicts",null,ContentValues().apply { put("id",hash);put("kind","record");put("entity_id",entity.getString("id"));put("payload",payload) },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
    mapOf("serverId" to server, "cursor" to cursor.toString()).forEach { (key,value) -> db.insertWithOnConflict("sync_meta", null, ContentValues().apply { put("key",key); put("value",value) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) } }
    changed
}
