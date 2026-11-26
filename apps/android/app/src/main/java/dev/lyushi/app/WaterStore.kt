package dev.lyushi.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.abs

internal const val MAX_WATER_ML = 10000
private const val MAX_WATER_TIME = 253402271999000L
private val waterID = Regex("^[a-f0-9-]{32,64}$")
private const val changed = "饮水状态已更新，请核对后重新操作"

internal fun initializeWater(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS water_local(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL)")
}

internal fun countsWater(kind: String) = kind == "drink" || kind == "manual"

private fun JSONObject.section(key: String) = optJSONObject(key) ?: JSONObject()
private fun JSONObject.group(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key,it) }
private fun JSONObject.rows(key: String) = optJSONArray(key)?.objects().orEmpty()

/** 在服务器投影或本地预览上应用一次变化，待核对内容不参与累计。 */
private fun applyChange(data: JSONObject, change: JSONObject) {
    change.optJSONObject("bottle")?.let { data.put("bottle",it) }
    change.optJSONObject("entry")?.let { data.group("entries").put(it.getString("id"),it) }
    if (change.has("conflict")) data.group("conflicts").put(change.getJSONObject("command").getString("id"),change)
    if (change.has("resolved")) data.section("conflicts").remove(change.getString("resolved"))
}

/** 用与服务端相同的前置版本在离线时预览，历史更正和补记不参与物理水位计算。 */
private fun preview(data: JSONObject, c: JSONObject): JSONObject {
    val id = c.getString("id")
    val kind = c.getString("kind")
    val target = c.optString("target")
    val at = c.getLong("at")
    val ml = c.optInt("ml", -1)
    val requestedCapacity = c.optInt("capacity")
    check(waterID.matches(id) && at in 1 until MAX_WATER_TIME) { "饮水记录编号或时间无效" }
    fun entry(entryId: String, type: String, amount: Int, whenAt: Long, voided: Boolean = false): JSONObject {
        check(amount in 1..MAX_WATER_ML && (countsWater(type) || type == "fill" || type == "pour")) { "饮水类型或毫升数无效" }
        return JSONObject().put("id",entryId).put("kind",type).put("ml",amount).put("at",whenAt).put("version",id).put("voided",voided)
    }
    val entries = data.section("entries")
    val result = JSONObject()
    when (kind) {
        "manual" -> return result.put("entry",entry(id,kind,ml,at))
        "restore-entry" -> {
            check(waterID.matches(target)) { "备份记录编号无效" }
            val next = entry(target,c.getString("entry"),ml,at,c.getBoolean("voided"))
            val existing = entries.optJSONObject(target)
            if (existing == null) return result.put("entry",next)
            next.put("version",existing.getString("version"))
            return if (canonical(existing) == canonical(next)) result else result.put("command",c).put("conflict","history_differs")
        }
        "correct", "void" -> {
            val previous = checkNotNull(entries.optJSONObject(target)) { changed }
            check(!previous.getBoolean("voided") && countsWater(previous.getString("kind")) && previous.getString("version") == c.optString("base")) { changed }
            val next = if (kind == "void") JSONObject(previous.toString()).put("voided",true).put("version",id) else entry(target,previous.getString("kind"),ml,at)
            return result.put("entry",next)
        }
        "recover", "dismiss" -> {
            val previous = checkNotNull(data.section("conflicts").optJSONObject(target)?.optJSONObject("command")) { changed }
            if (kind == "recover") {
                check(previous.getString("kind") == "drink") { "仅喝水操作可转为补记" }
                result.put("entry",entry(id,"manual",previous.optInt("from")-previous.optInt("ml"),previous.getLong("at")))
            }
            return result.put("resolved",target)
        }
    }
    val bottle = data.section("bottle")
    fun validCapacity() = check(requestedCapacity in 1..MAX_WATER_ML) { "请输入 1 至 10000 mL 的容量" }
    if (kind == "setup" || kind == "restore-bottle") {
        validCapacity()
        check(bottle.length() == 0 && ml in 0..requestedCapacity && (kind != "setup" || ml == 0)) { changed }
        return result.put("bottle",JSONObject().put("capacity",requestedCapacity).put("ml",ml).put("version",id))
    }
    check(bottle.length() > 0 && c.optString("base") == bottle.getString("version")) { changed }
    val capacity = bottle.getInt("capacity")
    val before = bottle.getInt("ml")
    val next = JSONObject().put("capacity",capacity).put("ml",before).put("version",id)
    val undo = JSONObject().put("capacity",capacity).put("ml",before)
    when (kind) {
        "capacity" -> {
            validCapacity()
            check(requestedCapacity >= before) { "请先喝水或倒水，使剩余量低于新容量" }
            if (requestedCapacity == capacity) return result
            next.put("capacity",requestedCapacity).put("undo",undo)
        }
        "calibrate" -> {
            check(ml in 0..capacity) { "剩余水量不能超过水壶容量" }
            if (ml == before) return result
            next.put("ml",ml).put("undo",undo)
        }
        "undo" -> {
            val prior = checkNotNull(bottle.optJSONObject("undo")) { "没有可撤销的水壶操作" }
            next.put("capacity",prior.getInt("capacity")).put("ml",prior.getInt("ml"))
            if (prior.has("entry")) {
                val previous = entries.getJSONObject(prior.getString("entry"))
                result.put("entry",JSONObject(previous.toString()).put("voided",true).put("version",id))
            }
        }
        else -> {
            check(ml in 0..capacity && c.optInt("from") == before && ((kind == "fill" && ml > before) || ((kind == "drink" || kind == "pour") && ml < before))) { changed }
            next.put("ml",ml).put("undo",undo.put("entry",id))
            result.put("entry",entry(id,kind,abs(ml-before),at))
        }
    }
    return result.put("bottle",next)
}

private fun Store.waterData(): JSONObject = readableDatabase.rawQuery("SELECT payload FROM water_local WHERE singleton=1",null).use {
    if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
}

private fun Store.waterIsBound(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM sync_meta WHERE key='boundServerId'",null).use { it.moveToFirst() }

private fun Store.saveWater(data: JSONObject) {
    writableDatabase.execSQL("INSERT OR REPLACE INTO water_local VALUES(1,?)",arrayOf(data.toString()))
}

private fun visible(data: JSONObject): JSONObject {
    for (op in data.rows("pending")) {
        // 新服务器水位已抢先更新时，保留原命令，暂停叠加已过期预览。
        try { applyChange(data,preview(data,op)) } catch (_: IllegalStateException) {}
    }
    return data
}

private fun command(kind: String, fields: JSONObject = JSONObject()): JSONObject = fields.put("kind",kind)
    .put("id",UUID.randomUUID().toString()).apply { if (!has("at")) put("at",System.currentTimeMillis()) }

internal fun Store.waterView(): JSONObject = visible(waterData())

internal fun Store.waterSubmit(kind: String, fields: JSONObject = JSONObject()) = transaction {
    val data = waterData()
    val operation = command(kind,fields)
    val change = preview(visible(JSONObject(data.toString())),operation)
    if (change.length() > 0) {
        if (waterIsBound()) data.put("pending",(data.optJSONArray("pending") ?: JSONArray()).put(operation)) else applyChange(data,change)
        saveWater(data)
    }
}

internal fun Store.waterRequest(serverId: String): JSONObject {
    val data = waterData()
    return JSONObject().put("serverId",serverId).put("cursor",data.optLong("cursor")).put("operations",JSONArray(data.rows("pending").take(50)))
}

/** 游标、投影和待发送操作同一次提交，分页落后时保留尚未出现回执的本地命令。 */
internal fun Store.waterAccept(serverId: String, reply: JSONObject): Boolean = transaction {
    val data = waterData()
    var cursor = data.optLong("cursor")
    check(reply.getString("serverId") == serverId && reply.get("more") is Boolean) { "饮水服务器响应无效" }
    val received = mutableSetOf<String>()
    for (change in reply.getJSONArray("changes").objects()) {
        val id = change.getJSONObject("command").getString("id")
        check(change.getLong("seq") == ++cursor && waterID.matches(id)) { "饮水同步顺序无效" }
        received.add(id); applyChange(data,change)
    }
    check(reply.getLong("cursor") == cursor) { "饮水游标无效" }
    val ack = reply.getJSONObject("ack")
    for (id in ack.keys()) {
        val seq = ack.getLong(id)
        check(waterID.matches(id) && seq > 0) { "饮水确认编号无效" }
        if (seq <= cursor) received.add(id)
    }
    if (received.isNotEmpty()) {
        data.put("cursor",cursor).put("pending",JSONArray(data.rows("pending").filter { it.getString("id") !in received }))
        saveWater(data)
    }
    received.isNotEmpty()
}

internal fun waterEntries(data: JSONObject): List<JSONObject> = data.section("entries").let { entries ->
    entries.keys().asSequence().map { entries.getJSONObject(it) }.sortedByDescending { it.getLong("at") }.toList()
}

internal fun Store.waterBackup(): JSONObject {
    val data = waterView(); val bottle = data.section("bottle")
    val portableBottle = JSONObject()
    if (bottle.length() > 0) portableBottle.put("capacity",bottle.getInt("capacity")).put("ml",bottle.getInt("ml"))
    return JSONObject().put("format","lyushi.water.backup").put("version",1).put("bottle",portableBottle).put("entries",JSONArray(waterEntries(data)))
}

/** 备份恢复在一笔事务核对全部明细，有水壶时仅导入历史，同号不同内容供用户核对。 */
internal fun Store.waterImport(backup: JSONObject) = transaction {
    check(backup.optString("format") == "lyushi.water.backup" && backup.optInt("version") == 1) { "饮水备份格式无效" }
    val data = waterData(); val view = visible(JSONObject(data.toString())); val bound = waterIsBound()
    val pending = data.optJSONArray("pending") ?: JSONArray()
    val queued = pending.objects().map { it.getString("id") }.toSet()
    fun restore(op: JSONObject, change: JSONObject) {
        if (change.length() == 0 || op.getString("id") in queued) return
        if (bound) pending.put(op) else applyChange(data,change)
    }
    val bottle = backup.getJSONObject("bottle")
    if (bottle.length() > 0) {
        val op = command("restore-bottle",JSONObject().put("capacity",strictWaterNumber(bottle,"capacity")).put("ml",strictWaterNumber(bottle,"ml")))
        val change = preview(JSONObject(),op)
        if (view.section("bottle").length() == 0) restore(op,change)
    }
    val seen = mutableSetOf<String>()
    for (entry in backup.getJSONArray("entries").objects()) {
        val id = entry.getString("id"); check(waterID.matches(id) && seen.add(id)) { "饮水备份明细无效或重复" }
        check(entry.get("voided") is Boolean) { "饮水备份作废状态无效" }
        val op = command("restore-entry",JSONObject().put("target",id).put("at",strictWaterNumber(entry,"at")).put("ml",strictWaterNumber(entry,"ml"))
            .put("entry",entry.getString("kind")).put("voided",entry.getBoolean("voided")))
        // 编号与桌面端按相同字段序列生成，重复导入及跨端重试均可去重。
        val identity = listOf("lyushi-water-restore-entry-v1",id,op.getString("entry"),op.getLong("at"),op.getInt("ml"),if(op.getBoolean("voided"))1 else 0).joinToString("\n")
        op.put("id",MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        restore(op,preview(view,op))
    }
    data.put("pending",pending); saveWater(data)
}

private fun strictWaterNumber(source: JSONObject,key: String): Long {
    val value = source.get(key)
    check(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0..(if (key == "at") MAX_WATER_TIME else MAX_WATER_ML.toLong())) {
        "饮水备份数值无效"
    }
    return value.toLong()
}
