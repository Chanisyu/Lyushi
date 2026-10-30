package dev.lyushi.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 数组复制为对象列表，游标和 JSON 内部迭代状态不泄漏给界面。 */
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
/** 确定性 JSON 用于比较内容及冲突去重，不依赖文件中的字段排列。 */
internal fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/**
 * 本机存储与桌面采用同一 JSON 数据约定。实体变更和待上传操作在同一事务保存。
 * 独立包名和数据库不会覆盖技术原型；删除保留状态供后续同步传播。
 */
internal class Store(context: Context, name: String = "lyushi.sqlite") : SQLiteOpenHelper(context, name, null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entities(kind TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conflicts(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,payload TEXT NOT NULL)")
        initializeSync(db, false)
    }
    /** 旧本地版只转换队列格式，账本与事项保持原样；升级事务失败会完整回滚。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "不支持的数据库升级" }; initializeSync(db, true)
    }
    /** 数据库事务提交失败时抛错，结束事务会回滚未标记成功的全部修改。 */
    internal fun <T> transaction(action: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = action(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    internal fun find(kind: String, id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT payload FROM entities WHERE kind=? AND id=?", arrayOf(kind, id)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    fun entities(kind: String, deleted: Boolean = false): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT payload FROM entities WHERE kind=? ORDER BY id", arrayOf(kind)
    ).use { cursor -> buildList { while (cursor.moveToNext()) { val item = JSONObject(cursor.getString(0)); if (deleted || !item.getBoolean("deleted")) add(item) } } }
    /** 只能由修改事务调用，防止记录已经保存却丢失对应待上传操作。 */
    private fun save(kind: String, entity: JSONObject) {
        val previous = find(kind, entity.getString("id"))
        val operation = UUID.randomUUID().toString()
        val parents = JSONArray(); previous?.optString("version")?.takeIf { it.isNotEmpty() }?.let { parents.put(it) }
        if (entity.getBoolean("deleted")) readableDatabase.rawQuery("SELECT version FROM sync_heads WHERE kind=? AND id=?", arrayOf(kind,entity.getString("id"))).use { cursor ->
            while (cursor.moveToNext()) { val version = cursor.getString(0); if ((0 until parents.length()).none { parents.getString(it) == version }) parents.put(version) }
        }
        entity.put("version", operation)
        entity.put("revision", if (previous == null) maxOf(1, entity.optLong("revision", 1)) else previous.getLong("revision") + 1)
        val values = ContentValues().apply { put("kind", kind); put("id", entity.getString("id")); put("payload", canonical(entity)) }
        writableDatabase.insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "记录保存失败" } }
        writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
            put("id", operation); put("kind", kind); put("entity_id", entity.getString("id"))
            put("payload", canonical(JSONObject().put("parents", parents).put("entity", entity)))
        })
    }
    /** 使用部分索引读取当前活动，历史记录变多后也不每秒扫描全部记录。 */
    fun active(): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM entities WHERE kind='record' AND json_extract(payload,'$.deleted')=0 AND json_extract(payload,'$.ended') IS NULL ORDER BY json_extract(payload,'$.started') LIMIT 1",null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun idle() { check(active() == null) { "请先结束当前记录" } }
    /** 账本名称不是身份，同名账本保持独立。 */
    fun addLedger(name: String): String = transaction {
        idle(); check(name.isNotBlank() && name.length <= 200) { "请输入不超过 200 字的账本名" }
        val id = UUID.randomUUID().toString()
        save("ledger", JSONObject().put("id", id).put("name", name.trim()).put("deleted", false).put("revision", 1)); id
    }
    fun renameLedger(id: String, name: String) = transaction {
        idle(); check(name.isNotBlank() && name.length <= 200) { "请输入不超过 200 字的账本名" }
        val ledger = checkNotNull(find("ledger", id)); check(!ledger.getBoolean("deleted")); save("ledger", ledger.put("name", name.trim()))
    }
    /** 整本删除与其记录删除一次提交，其他账本内容保持原样。 */
    fun deleteLedger(id: String) = transaction {
        idle(); val ledger = checkNotNull(find("ledger", id))
        entities("record").filter { it.getString("ledgerId") == id }.forEach { save("record", it.put("deleted", true)) }
        save("ledger", ledger.put("deleted", true))
    }
    fun start(ledgerId: String, task: String) = transaction {
        idle(); val ledger = checkNotNull(find("ledger", ledgerId)) { "请先选择账本" }; check(!ledger.getBoolean("deleted"))
        check(task.isNotBlank() && task.length <= 10000) { "请输入不超过一万字的事项" }
        save("record", JSONObject().put("id", UUID.randomUUID().toString()).put("ledgerId", ledgerId).put("task", task.trim())
            .put("started", System.currentTimeMillis()).put("ended", JSONObject.NULL).put("deleted", false).put("revision", 1))
    }
    fun finish(ended: Long = System.currentTimeMillis()) = transaction {
        val record = checkNotNull(active()) { "没有进行中的记录" }; record.put("ended", ended); validateRecord(record); save("record", record)
    }
    fun edit(id: String?, ledgerId: String, task: String, started: Long, ended: Long, expectedVersion: String? = null) = transaction {
        idle(); val ledger = checkNotNull(find("ledger", ledgerId)); check(!ledger.getBoolean("deleted"))
        val record = if (id == null) JSONObject().put("id", UUID.randomUUID().toString()).put("deleted", false).put("revision", 1)
            else checkNotNull(find("record", id)) { "记录不存在" }.also { check(!it.getBoolean("deleted") && it.getString("ledgerId") == ledgerId) }
        check(expectedVersion.isNullOrEmpty() || record.optString("version") == expectedVersion) { "记录已被另一设备更新，请保留输入并重新打开修改" }
        record.put("ledgerId", ledgerId).put("task", task.trim()).put("started", started).put("ended", ended)
        validateRecord(record); save("record", record)
    }
    fun deleteRecord(id: String) = transaction { idle(); save("record", checkNotNull(find("record", id)).put("deleted", true)) }
    fun conflicts(): List<JSONObject> = readableDatabase.rawQuery("SELECT payload FROM conflicts ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }
    /** 状态栏只判断是否存在冲突，不在每秒刷新时读取全部存档。 */
    fun hasConflicts(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM conflicts LIMIT 1",null).use { it.moveToFirst() }
    fun backup(): JSONObject {
        idle(); return JSONObject().put("format", "lyushi.backup").put("formatVersion", 1).put("ledgers", JSONArray(entities("ledger", true)))
            .put("records", JSONArray(entities("record", true))).put("conflicts", JSONArray(conflicts()))
    }
    /** 输入必须是整数 JSON 数值，不能用字符串、布尔或浮点时间冒充。 */
    private fun integer(entity: JSONObject, key: String): Long {
        val value = entity.get(key); check(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "无效整数：$key" }; return value.toLong()
    }
    private fun validateEntity(entity: JSONObject) {
        check(entity.get("id") is String && entity.getString("id").isNotEmpty() && entity.getString("id").length <= 128) { "无效编号" }
        check(entity.get("deleted") is Boolean && integer(entity, "revision") in 1..9007199254740991L) { "版本或删除状态无效" }
        check(!entity.has("version") || (entity.get("version") is String && entity.getString("version").length in 1..128)) { "版本编号无效" }
    }
    /** 严格识别协议字段，避免接受服务器无法处理的扩展字段并卡住上传队列。 */
    private fun onlyFields(entity: JSONObject, allowed: Set<String>) { check(entity.keys().asSequence().all { it in allowed }) { "备份包含当前版本不支持的字段" } }
    private fun validateLedger(entity: JSONObject) {
        validateEntity(entity); onlyFields(entity,setOf("id","name","deleted","revision","version"))
        check(entity.get("name") is String && entity.getString("name").isNotBlank() && entity.getString("name").length <= 200) { "账本名称无效" }
    }
    private fun validateRecord(entity: JSONObject, allowRunning: Boolean = false) {
        validateEntity(entity)
        onlyFields(entity,setOf("id","ledgerId","task","started","ended","deleted","revision","version"))
        check(entity.get("task") is String && entity.getString("task").isNotBlank() && entity.getString("task").length <= 10000) { "事项不能为空，且不能超过一万字" }
        check(entity.get("ledgerId") is String && entity.getString("ledgerId").length in 1..128) { "记录缺少有效账本编号" }
        val start = integer(entity, "started")
        check(start >= 0 && start < 253402271999000L) { "开始时间无效" }
        if (allowRunning && entity.has("ended") && entity.isNull("ended")) return
        val end = integer(entity, "ended"); check(end >= start && end < 253402271999000L) { "起止时间无效；结束不能早于开始" }
    }
    /** 整批校验与合并使用同一事务；冲突另存并参与备份，绝不以旧内容覆盖当前版本。 */
    fun importBackup(data: JSONObject): JSONObject = transaction {
        idle(); check(data.getString("format") == "lyushi.backup" && integer(data, "formatVersion") == 1L) { "不支持的备份格式" }
        val ledgers = data.getJSONArray("ledgers").objects(); val records = data.getJSONArray("records").objects()
        val ledgerIds = mutableSetOf<String>(); val recordIds = mutableSetOf<String>()
        ledgers.forEach { validateLedger(it); check(ledgerIds.add(it.getString("id"))) { "重复账本编号" } }
        records.forEach { validateRecord(it); check(it.getString("ledgerId") in ledgerIds) { "记录缺少所属账本" }; check(recordIds.add(it.getString("id"))) { "重复记录编号" } }
        var added = 0; var duplicate = 0; var conflictCount = 0
        fun preserve(kind: String, entity: JSONObject, archived: Boolean = false) {
            check(kind == "record" || kind == "ledger") { "冲突类型无效" }
            if (kind == "record") validateRecord(entity, archived) else validateLedger(entity)
            val wrapped = JSONObject().put("kind", kind).put("entity", entity).put("archived", true)
            val payload = canonical(wrapped)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val result = writableDatabase.insertWithOnConflict("conflicts", null, ContentValues().apply {
                put("id", hash); put("kind", kind); put("entity_id", entity.getString("id")); put("payload", payload)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (result != -1L) { conflictCount++; queueArchive(writableDatabase,wrapped) }
        }
        listOf("ledger" to ledgers, "record" to records).forEach { (kind, items) -> items.forEach { entity ->
            val local = find(kind, entity.getString("id"))
            val deletedParent = kind == "record" && find("ledger", entity.getString("ledgerId"))?.getBoolean("deleted") == true && !entity.getBoolean("deleted")
            fun content(item: JSONObject) = JSONObject(item.toString()).apply { remove("version"); remove("revision") }
            if (local != null && canonical(content(local)) == canonical(content(entity))) duplicate++
            else if (local == null && !deletedParent) { save(kind, entity); added++ }
            else preserve(kind, entity)
        } }
        data.getJSONArray("conflicts").objects().forEach { preserve(it.getString("kind"), it.getJSONObject("entity"), it.optBoolean("archived", false)) }
        JSONObject().put("added", added).put("duplicate", duplicate).put("conflicts", conflictCount)
    }
    /** 带 UTF-8 标记及标准 CSV 转义；公式型文本加前缀使表格按文字显示。 */
    fun csv(ledgerId: String?): String {
        idle()
        fun quote(value: String): String { val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value; return "\"${safe.replace("\"", "\"\"")}\"" }
        return buildString {
            append("\uFEFF账本,事项,开始（北京时间）,结束（北京时间）,时长（秒）,记录编号\r\n")
            entities("record").filter { ledgerId == null || it.getString("ledgerId") == ledgerId }.forEach { record ->
                val ledger = checkNotNull(find("ledger", record.getString("ledgerId")))
                if (!ledger.getBoolean("deleted")) append(listOf(ledger.getString("name"), record.getString("task"), dateText(record.getLong("started"), true),
                    dateText(record.getLong("ended"), true), java.lang.String.format(java.util.Locale.ROOT, "%.3f", (record.getLong("ended") - record.getLong("started")) / 1000.0), record.getString("id")).joinToString(",") { quote(it) } + "\r\n")
            }
        }
    }
}

/** 所有页面和导出固定使用 UTC+8，不依赖设备选择的时区。 */
internal fun dateText(ms: Long, precise: Boolean = false): String = DateTimeFormatter.ofPattern(if (precise) "yyyy-MM-dd HH:mm:ss.SSS" else "yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneOffset.ofHours(8)).format(Instant.ofEpochMilli(ms))
internal fun durationText(ms: Long): String { val s = maxOf(0, ms / 1000); return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
