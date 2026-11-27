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

/** 计时按钮的文案和操作共用同一份界面快照。活动操作还必须携带显示时的记录编号和版本。 */
internal sealed interface TimerAction {
    data object Start : TimerAction
    data class Finish(val id: String, val version: String) : TimerAction
    data class Resume(val id: String, val version: String) : TimerAction

    companion object {
        fun displayed(record: JSONObject?): TimerAction {
            if (record == null) return Start
            val id = record.getString("id")
            val version = record.getString("version")
            return if (isDistracted(record)) Resume(id, version) else Finish(id, version)
        }
    }
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
    /** 饮水仅增加独立表，原版本仍能读取本机时间账本。 */
    override fun onOpen(db: SQLiteDatabase) { super.onOpen(db); initializeWater(db) }
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
    /** 固定账本编号读取；过期的改名和删除确认在修改事务内拒绝。 */
    fun liveLedger(id: String, expectedVersion: String? = null): JSONObject {
        val ledger = find("ledger", id)
        check(ledger != null && !ledger.getBoolean("deleted")) { "此账本已不存在，请返回并重新选择" }
        check(expectedVersion == null || ledger.optString("version") == expectedVersion) { "此账本已更新，请重新打开明细后操作" }
        return ledger
    }
    /** 账本名称不是身份，同名账本保持独立。 */
    fun addLedger(name: String): String = transaction {
        idle(); check(name.isNotBlank() && name.length <= 200) { "请输入不超过 200 字的账本名" }
        val id = UUID.randomUUID().toString()
        save("ledger", JSONObject().put("id", id).put("name", name.trim()).put("deleted", false).put("revision", 1)); id
    }
    fun renameLedger(id: String, name: String, expectedVersion: String? = null) = transaction {
        idle(); check(name.isNotBlank() && name.length <= 200) { "请输入不超过 200 字的账本名" }
        val ledger = liveLedger(id, expectedVersion); save("ledger", ledger.put("name", name.trim()))
    }
    /** 整本删除与其记录删除一次提交，其他账本内容保持原样。 */
    fun deleteLedger(id: String, expectedVersion: String? = null) = transaction {
        idle(); val ledger = liveLedger(id, expectedVersion)
        entities("record").filter { it.getString("ledgerId") == id }.forEach { save("record", it.put("deleted", true)) }
        save("ledger", ledger.put("deleted", true))
    }
    /** 与桌面端按修剪后名称的 UTF-8 字节生成相同身份，防止多端同名标签重复。 */
    fun addTag(name: String): String = transaction {
        idle(); val text = name.trim(); check(text.isNotEmpty() && text.length <= 200) { "请输入不超过 200 字的标签" }
        val id = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val previous = find("tag", id)
        if (previous == null || previous.getBoolean("deleted")) save("tag", JSONObject().put("id", id).put("name", text).put("deleted", false).put("revision", 1))
        text
    }
    /** 删除只影响候选列表；记录中的历史标签快照保持不变。 */
    fun deleteTag(name: String) = transaction {
        idle(); val matches = entities("tag").filter { it.getString("name") == name }
        check(matches.isNotEmpty()) { "标签已被删除，请重新选择" }
        matches.forEach { save("tag", it.put("deleted", true)) }
    }
    fun start(ledgerId: String, task: String, tag: String = "") = transaction {
        idle(); val ledger = checkNotNull(find("ledger", ledgerId)) { "请先选择账本" }; check(!ledger.getBoolean("deleted"))
        check(task.isNotBlank() && task.length <= 10000) { "请输入不超过一万字的事项" }
        check(tag.isEmpty() || entities("tag").any { it.getString("name") == tag }) { "标签已被删除，请重新选择" }
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("ledgerId", ledgerId).put("task", task.trim())
            .put("started", System.currentTimeMillis()).put("ended", JSONObject.NULL).put("deleted", false).put("revision", 1)
        if (tag.isNotEmpty()) record.put("tag", tag)
        save("record", record)
    }
    /** 点击后只执行已展示的动作。各写入事务自行核对空闲状态或活动记录的编号、版本。 */
    fun applyTimerAction(action: TimerAction, ledgerId: String, task: String, tag: String) {
        when (action) {
            TimerAction.Start -> start(ledgerId, task, tag)
            is TimerAction.Finish -> finish(expectedVersion = action.version, expectedId = action.id, requireFocus = true)
            is TimerAction.Resume -> resumeFocus(System.currentTimeMillis(), action.version, action.id)
        }
    }

    fun finish(ended: Long = System.currentTimeMillis(), expectedVersion: String? = null, expectedId: String? = null, requireFocus: Boolean = false) = transaction {
        val record = timerTarget(expectedVersion, expectedId)
        check(!requireFocus || !isDistracted(record)) { "计时阶段已更新，请核对页面后再操作" }
        if (record.has("segments")) { val parts = record.getJSONArray("segments"); parts.getJSONObject(parts.length()-1).put("ended", ended) }
        record.put("ended", ended); validateRecord(record); save("record", record)
    }

    /** 只供计时写入事务调用，核对和保存之间不允许同步线程切换活动记录。 */
    private fun timerTarget(expectedVersion: String?, expectedId: String?): JSONObject {
        val record = checkNotNull(active()) { "没有进行中的记录，请核对页面后再操作" }
        check((expectedId == null || record.getString("id") == expectedId) &&
              (expectedVersion == null || record.getString("version") == expectedVersion)) {
            "任务已更新，请核对页面后再操作"
        }
        return record
    }
    fun edit(id: String?, ledgerId: String, task: String, started: Long, ended: Long, expectedVersion: String? = null, tag: String? = null) = transaction {
        idle(); liveLedger(ledgerId)
        val record = if (id == null) JSONObject().put("id", UUID.randomUUID().toString()).put("deleted", false).put("revision", 1)
            else checkNotNull(find("record", id)) { "记录不存在" }.also { check(!it.getBoolean("deleted") && it.getString("ledgerId") == ledgerId) }
        check(expectedVersion.isNullOrEmpty() || record.optString("version") == expectedVersion) { "记录已被另一设备更新，请保留输入并重新打开修改" }
        // 空参数表示旧调用保留标签；空字符串表示用户明确选择“无标签”。
        if (tag != null) {
            check(tag.isEmpty() || record.optString("tag") == tag || entities("tag").any { it.getString("name") == tag }) { "标签已被删除，请重新选择" }
            if (tag.isEmpty()) record.remove("tag") else record.put("tag", tag)
        }
        check(!record.has("segments") || (record.getLong("started") == started && record.getLong("ended") == ended)) { "请在分段明细中修改起止时间" }
        record.put("ledgerId", ledgerId).put("task", task.trim()).put("started", started).put("ended", ended)
        validateRecord(record); save("record", record)
    }
    /** 关闭原专注区间并创建一层分心；整个切换仅写一个事务和一个记录版本。 */
    fun distract(task: String, tag: String, now: Long, expectedVersion: String) = transaction {
        val record = checkNotNull(active()); check(!isDistracted(record)) { "请先返回原任务" }
        check(record.optString("version") == expectedVersion) { "任务已由其他设备更新，请核对后重试" }
        check(tag.isEmpty() || entities("tag").any { it.getString("name") == tag }) { "标签已被删除，请重新选择" }
        val parts = taskSegments(record); parts.last().put("ended", now)
        val next = JSONObject().put("kind", "distraction").put("task", task.trim().ifEmpty { "分心" }).put("started", now).put("ended", JSONObject.NULL)
        if (tag.isNotEmpty()) next.put("tag", tag)
        record.put("segments", JSONArray(parts + next)); validateTransition(record); save("record", record)
    }
    /** 同一时间点结束分心并恢复原任务，版本检查阻止重复恢复和过期按钮操作。 */
    fun resumeFocus(now: Long, expectedVersion: String, expectedId: String? = null) = transaction {
        val record = timerTarget(expectedVersion, expectedId)
        check(isDistracted(record)) { "当前没有可结束的分心" }
        val parts = taskSegments(record); parts.last().put("ended", now)
        record.put("segments", JSONArray(parts + JSONObject().put("kind", "focus").put("started", now).put("ended", JSONObject.NULL)))
        validateTransition(record); save("record", record)
    }
    /**
     * 仅更正当前阶段元数据；专注修改根事项，分心修改最后一段的独立快照。
     * 编号与版本必须同时匹配，结束、阶段切换或远端修改后均拒绝旧表单。
     * 不读取当前时间，不改区间和账本；校验失败回滚实体与队列，无变化不产生版本。
     */
    fun editActiveMetadata(id: String, expectedVersion: String, task: String, tag: String) = transaction {
        val record = active()
        check(id.isNotEmpty() && expectedVersion.isNotEmpty() && record?.optString("id") == id && record.optString("version") == expectedVersion) {
            "任务状态已更新，输入已保留。请核对主页面，取消后重新打开修改"
        }
        val before = canonical(record!!)
        val parts = taskSegments(record); val distracted = isDistracted(record)
        val target = if (distracted) parts.last() else record
        check(tag.isEmpty() || target.optString("tag") == tag || entities("tag").any { it.getString("name") == tag }) { "标签已被删除，请重新选择；输入已保留" }
        target.put("task", task.trim()); if (tag.isEmpty()) target.remove("tag") else target.put("tag", tag)
        if (distracted) record.put("segments", JSONArray(parts))
        validateTransition(record)
        if (canonical(record) != before) save("record", record)
    }
    /** 为结束字段及版本外壳预留空间，分心过多时仍允许结束整个任务。 */
    private fun validateTransition(record: JSONObject) {
        check(taskPayloadSize(record) <= 500 * 1024) { "任务分段数据已达上限，请结束后新建任务" }
        validateRecord(record, true)
    }
    /** 结束后的区间可以独立修改时间；修改分心不会重写原任务名称。 */
    fun editSegment(id: String, index: Int, task: String, tag: String, started: Long, ended: Long, expectedVersion: String) = transaction {
        idle(); val record = checkNotNull(find("record", id)); check(!record.getBoolean("deleted") && !record.isNull("ended"))
        check(record.optString("version") == expectedVersion) { "记录已更新，请重新打开分段明细" }
        val parts = taskSegments(record); check(index in parts.indices); val part = parts[index]
        part.put("started", started).put("ended", ended)
        val target = if (part.getString("kind") == "distraction") part else record
        target.put("task", task.trim()); if (tag.isEmpty()) target.remove("tag") else target.put("tag", tag)
        if (parts.size > 1) record.put("segments", JSONArray(parts))
        record.put("started", parts.first().get("started")).put("ended", parts.last().get("ended"))
        validateRecord(record); save("record", record)
    }
    /** 严格约束区间数量、体积、顺序和结束状态，确保聚合始终能够整体同步。 */
    private fun validateSegments(record: JSONObject, allowRunning: Boolean) {
        if (!record.has("segments")) return
        check(record.get("segments") is JSONArray) { "分段格式无效" }; val parts = record.getJSONArray("segments").objects()
        check(parts.size in 2..256 && taskPayloadSize(record) <= 512 * 1024) { "任务分段超过上限，请结束后新建任务" }
        var previousEnd = record.getLong("started")
        parts.forEachIndexed { i, part ->
            val distraction = i % 2 == 1
            onlyFields(part, if (distraction) setOf("kind","started","ended","task","tag") else setOf("kind","started","ended"))
            check(part.getString("kind") == if (distraction) "distraction" else "focus") { "分心仅支持一层且必须与专注交替" }
            val entry = segmentRecord(record, i); validateRecord(entry, allowRunning && i == parts.lastIndex)
            check(entry.getLong("started") >= previousEnd && (i != 0 || entry.getLong("started") == record.getLong("started"))) { "分段时间重叠或顺序无效" }
            previousEnd = if (entry.isNull("ended")) 0 else entry.getLong("ended")
        }
        check(parts.last().get("ended").toString() == record.get("ended").toString()) { "任务结束时间与最后区间不一致" }
    }
    fun deleteRecord(id: String) = transaction { idle(); save("record", checkNotNull(find("record", id)).put("deleted", true)) }
    fun conflicts(): List<JSONObject> = readableDatabase.rawQuery("SELECT payload FROM conflicts ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }
    /** 状态栏只判断是否存在冲突，不在每秒刷新时读取全部存档。 */
    fun hasConflicts(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM conflicts LIMIT 1",null).use { it.moveToFirst() }
    fun backup(): JSONObject {
        idle(); return JSONObject().put("format", "lyushi.backup").put("formatVersion", 3).put("tags", JSONArray(entities("tag", true))).put("ledgers", JSONArray(entities("ledger", true)))
            .put("records", JSONArray(entities("record", true))).put("conflicts", JSONArray(conflicts()))
    }
    /** 输入必须是整数 JSON 数值，不能用字符串、布尔或浮点时间冒充。 */
    private fun integer(entity: JSONObject, key: String): Long {
        val value = entity.get(key); check(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "无效整数：$key" }; return value.toLong()
    }
    /** Unicode 分隔符加制表、换行和下一行控制符，与服务器的空白规则一致。有效编号保留原文。 */
    private fun validIdentifier(value: Any?): Boolean = value is String && value.length <= 128 && value.any {
        !Character.isSpaceChar(it) && it !in '\u0009'..'\u000d' && it != '\u0085'
    }

    private fun validateEntity(entity: JSONObject) {
        check(validIdentifier(entity.opt("id"))) { "无效编号" }
        check(entity.get("deleted") is Boolean && integer(entity, "revision") in 1..9007199254740991L) { "版本或删除状态无效" }
        check(!entity.has("version") || validIdentifier(entity.opt("version"))) { "版本编号无效" }
    }
    /** 严格识别协议字段，避免接受服务器无法处理的扩展字段并卡住上传队列。 */
    private fun onlyFields(entity: JSONObject, allowed: Set<String>) { check(entity.keys().asSequence().all { it in allowed }) { "备份包含当前版本不支持的字段" } }
    internal fun validateLedger(entity: JSONObject) {
        validateEntity(entity); onlyFields(entity,setOf("id","name","deleted","revision","version"))
        check(entity.get("name") is String && entity.getString("name").isNotBlank() && entity.getString("name").length <= 200) { "账本名称无效" }
    }
    internal fun validateRecord(entity: JSONObject, allowRunning: Boolean = false) {
        validateEntity(entity)
        onlyFields(entity,setOf("id","ledgerId","task","started","ended","deleted","revision","version","tag","segments"))
        check(!entity.has("tag") || (entity.get("tag") is String && entity.getString("tag").isNotBlank() && entity.getString("tag").length <= 200)) { "任务标签无效" }
        check(entity.get("task") is String && entity.getString("task").isNotBlank() && entity.getString("task").length <= 10000) { "事项不能为空，且不能超过一万字" }
        check(validIdentifier(entity.opt("ledgerId"))) { "记录缺少有效账本编号" }
        val start = integer(entity, "started")
        check(start >= 0 && start < 253402271999000L) { "开始时间无效" }
        validateSegments(entity, allowRunning)
        if (allowRunning && entity.has("ended") && entity.isNull("ended")) return
        val end = integer(entity, "ended"); check(end >= start && end < 253402271999000L) { "起止时间无效；结束不能早于开始" }
    }
    /** 整批校验与合并使用同一事务；冲突另存并参与备份，绝不以旧内容覆盖当前版本。 */
    fun importBackup(data: JSONObject): JSONObject = transaction {
        idle(); check(data.getString("format") == "lyushi.backup" && (integer(data, "formatVersion") == 1L || (integer(data, "formatVersion") in 2L..3L && data.opt("tags") is JSONArray))) { "不支持的备份格式" }
        val ledgers = data.getJSONArray("ledgers").objects(); val records = data.getJSONArray("records").objects()
        val ledgerIds = mutableSetOf<String>(); val recordIds = mutableSetOf<String>()
        ledgers.forEach { validateLedger(it); check(ledgerIds.add(it.getString("id"))) { "重复账本编号" } }
        records.forEach { validateRecord(it); check(it.getString("ledgerId") in ledgerIds) { "记录缺少所属账本" }; check(recordIds.add(it.getString("id"))) { "重复记录编号" } }
        val tags = data.optJSONArray("tags")?.objects() ?: emptyList()
        val tagIds = mutableSetOf<String>()
        tags.forEach { validateLedger(it); check(tagIds.add(it.getString("id"))) { "重复标签编号" } }
        var added = 0; var duplicate = 0; var conflictCount = 0
        fun preserve(kind: String, entity: JSONObject, archived: Boolean = false) {
            check(kind == "record" || kind == "ledger" || kind == "tag") { "冲突类型无效" }
            if (kind == "record") validateRecord(entity, archived) else validateLedger(entity)
            val wrapped = JSONObject().put("kind", kind).put("entity", entity).put("archived", true)
            val payload = canonical(wrapped)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val result = writableDatabase.insertWithOnConflict("conflicts", null, ContentValues().apply {
                put("id", hash); put("kind", kind); put("entity_id", entity.getString("id")); put("payload", payload)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (result != -1L) { conflictCount++; queueArchive(writableDatabase,wrapped) }
        }
        listOf("tag" to tags, "ledger" to ledgers, "record" to records).forEach { (kind, items) -> items.forEach { entity ->
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
        // 单账本的文件选择结束后核对原账本，找不到时拒绝导出。
        if (ledgerId != null) liveLedger(ledgerId)
        fun quote(value: String): String { val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value; return "\"${safe.replace("\"", "\"\"")}\"" }
        return buildString {
            append("\uFEFF账本,事项,开始（北京时间）,结束（北京时间）,时长（秒）,记录编号,标签,区间序号,区间类型\r\n")
            entities("record").filter { ledgerId == null || it.getString("ledgerId") == ledgerId }.forEach { record ->
                val ledger = checkNotNull(find("ledger", record.getString("ledgerId")))
                // 每个区间单独导出，保留统一任务编号；不生成汇总行，避免表格求和重复计时。
                if (!ledger.getBoolean("deleted")) taskSegments(record).forEachIndexed { index, part ->
                    val row = segmentRecord(record, index)
                    append(listOf(ledger.getString("name"), row.getString("task"), dateText(row.getLong("started"), true),
                        dateText(row.getLong("ended"), true), java.lang.String.format(java.util.Locale.ROOT, "%.3f", (row.getLong("ended") - row.getLong("started")) / 1000.0), record.getString("id"), row.optString("tag"), (index+1).toString(), if (part.getString("kind") == "focus") "专注" else "分心").joinToString(",") { quote(it) } + "\r\n")
                }
            }
        }
    }
}

/** 所有页面和导出固定使用 UTC+8，不依赖设备选择的时区。 */
internal fun dateText(ms: Long, precise: Boolean = false): String = DateTimeFormatter.ofPattern(if (precise) "yyyy-MM-dd HH:mm:ss.SSS" else "yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneOffset.ofHours(8)).format(Instant.ofEpochMilli(ms))
internal fun durationText(ms: Long): String { val s = maxOf(0, ms / 1000); return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
