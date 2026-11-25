package dev.lyushi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 独立数据库验证分心、恢复、异常回滚及备份；不读取正式用户数据或同步密钥。 */
internal fun runFocusChecks(context: Context) {
    listOf("focus-check.sqlite", "focus-restore.sqlite").forEach { context.deleteDatabase(it) }
    var start = 0L
    var id = ""
    var book = ""
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "非法分心操作未被拒绝" } }
    Store(context,"focus-check.sqlite").use { store ->
        book = store.addLedger("分心验收"); store.addTag("学习"); store.addTag("生活")
        store.start(book,"背单词","学习"); val original = store.active()!!
        start = original.getLong("started"); id = original.getString("id"); val version = original.getString("version")
        val pending = store.pendingCount()
        rejects { store.distract("点饭","",start+1200000,"stale") }
        rejects { store.distract("点饭","",start-1,version) }
        check(store.pendingCount() == pending && canonical(store.active()!!) == canonical(original))
        store.distract("点饭","生活",start+1200000,version)
        val current = store.active()!!
        check(isDistracted(current) && current.getString("id") == id && focusDuration(current,start+1500000) == 1200000L)
        rejects { store.distract("嵌套","",start+1200001,current.getString("version")) }
        rejects { store.addLedger("锁定") }
    }
    Store(context,"focus-check.sqlite").use { store ->
        var current = store.active()!!
        check(isDistracted(current) && current.getString("tag") == "学习")
        val version = current.getString("version"); store.resumeFocus(start+1500000,version)
        rejects { store.resumeFocus(start+1500000,version) }
        current = store.active()!!
        check(!isDistracted(current) && current.getString("task") == "背单词" && current.getString("tag") == "学习")
        store.finish(start+2400000,current.getString("version"))
        var record = store.find("record",id)!!
        check(focusDuration(record,start+2400000) == 2100000L && taskSegments(record).size == 3 && store.entities("record").size == 1)
        rejects { store.editSegment(id,1,"bad","",start+1199999,start+1500000,record.getString("version")) }
        store.editSegment(id,0,"复习单词","学习",start,start+1200000,record.getString("version"))
        record = store.find("record",id)!!
        check(segmentRecord(record,2).getString("task") == "复习单词" && segmentRecord(record,1).getString("task") == "点饭")
        // 按服务端转义后的体积拒绝过大任务，确保本机保存的数据始终能够通过协议校验。
        val large = JSONObject(record.toString()).put("ended",start+18000)
        large.put("segments",JSONArray((0 until 18).map { i -> JSONObject().put("kind",if (i%2 == 0) "focus" else "distraction").put("started",start+i*1000).put("ended",start+(i+1)*1000).apply { if (i%2 == 1) put("task","&".repeat(10000)) } }))
        rejects { store.validateRecord(large) }
        val backup = store.backup(); check(backup.getInt("formatVersion") == 3)
        // 输出仅含独立验收记录的备份，供桌面端验证格式 3 双向互操作。
        java.io.File(context.filesDir,"focus-test-backup.json").writeText(backup.toString(2),Charsets.UTF_8)
        Store(context,"focus-restore.sqlite").use { restored ->
            restored.importBackup(backup); check(restored.importBackup(backup).getInt("added") == 0)
            check(taskSegments(restored.find("record",id)!!).size == 3 && restored.csv(null).split("\r\n").size == 5)
        }
        // 同步返回的未结束分心应整体接管，任何非法区间都必须回滚响应事务。
        val operations = store.syncRequest().getJSONArray("operations"); val changes = JSONArray(); val ack = JSONArray()
        operations.objects().forEachIndexed { index, op -> ack.put(op.getString("id")); changes.put(JSONObject().put("seq",index+1).put("kind",op.getString("kind")).put("id",op.getString("entityId")).put("heads",JSONArray().put(op.getJSONObject("entity")))) }
        var seq = operations.length()
        store.acceptSync(JSONObject().put("serverId","focus-server").put("cursor",seq).put("changes",changes).put("ack",ack).put("more",false),operations)
        val remote = JSONObject(record.toString()).put("version","remote-focus").put("ended",JSONObject.NULL)
        remote.getJSONArray("segments").getJSONObject(2).put("ended",JSONObject.NULL)
        fun deliver(entity: JSONObject, cursor: Int) = store.acceptSync(JSONObject().put("serverId","focus-server").put("cursor",cursor).put("ack",JSONArray()).put("more",false).put("changes",JSONArray().put(JSONObject().put("seq",cursor).put("kind","record").put("id",id).put("heads",JSONArray().put(entity)))),JSONArray())
        deliver(remote,++seq); check(store.active() != null)
        val bad = JSONObject(remote.toString()); bad.getJSONArray("segments").getJSONObject(1).put("started",start-1)
        rejects { deliver(bad,seq+1) }; check(store.active()!!.getString("version") == "remote-focus")
        store.finish(start+2400000); store.deleteRecord(id)
        check(store.entities("record").isEmpty() && taskSegments(store.find("record",id)!!).size == 3)
        store.start(book,"再次记录"); current = store.active()!!; val next = current.getLong("started")
        store.distract("","",next+1000,current.getString("version")); current = store.active()!!
        check(segmentRecord(current,1).getString("task") == "分心")
        store.resumeFocus(next+2000,current.getString("version")); current = store.active()!!
        store.distract("第二次","",next+3000,current.getString("version")); store.finish(next+4000)
        check(store.entities("record").single().getJSONArray("segments").length() == 4)
    }
}
