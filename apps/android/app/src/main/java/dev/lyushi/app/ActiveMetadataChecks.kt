package dev.lyushi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 使用真实 SQLite 验证元数据范围、过期保存、无副作用失败与备份恢复。 */
internal fun runActiveMetadataChecks(context: Context) {
    val database = "active-metadata-check.sqlite"
    listOf(database,"active-metadata-restore.sqlite","active-tag-race.sqlite").forEach { context.deleteDatabase(it) }
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "非法活动修改未被拒绝" } }
    fun content(record: JSONObject): String = canonical(JSONObject(record.toString()).apply { remove("version"); remove("revision") })
    var saved = JSONObject()
    Store(context,database).use { store ->
        val book = store.addLedger("修改验收"); store.addTag("学习"); store.addTag("生活"); store.start(book,"背单词","学习")
        var current = store.active()!!; val id = current.getString("id"); val start = current.getLong("started")
        fun edit(name: String, tag: String) { store.editActiveMetadata(id,store.active()!!.getString("version"),name,tag) }
        val pending = store.pendingCount()
        rejects { store.editActiveMetadata("other",current.getString("version"),"误改","") }; rejects { store.editActiveMetadata(id,"","误改","") }
        rejects { edit("   ","") }; rejects { edit("x".repeat(10001),"") }; rejects { edit("更正","不存在") }
        check(canonical(store.active()!!) == canonical(current) && store.pendingCount() == pending)
        edit(" 背单词 ","学习"); check(canonical(store.active()!!) == canonical(current) && store.pendingCount() == pending)
        edit("复习单词",""); var expected = JSONObject(current.toString()).put("task","复习单词").apply { remove("tag") }
        check(content(store.active()!!) == content(expected) && store.pendingCount() == pending+1)
        rejects { store.editActiveMetadata(id,current.getString("version"),"旧表单","") }
        current = store.active()!!; store.distract("点饭","生活",start+1000,current.getString("version")); current = store.active()!!
        edit("选午餐",""); expected = JSONObject(current.toString()); expected.getJSONArray("segments").getJSONObject(1).put("task","选午餐").remove("tag")
        check(content(store.active()!!) == content(expected))
        current = store.active()!!; store.resumeFocus(start+2000,current.getString("version"))
        rejects { store.editActiveMetadata(id,current.getString("version"),"过期分心","生活") }
        current = store.active()!!; edit("英语复习","学习"); expected = JSONObject(current.toString()).put("task","英语复习").put("tag","学习")
        check(content(store.active()!!) == content(expected) && segmentRecord(store.active()!!,0).getString("task") == "英语复习" && segmentRecord(store.active()!!,2).getString("tag") == "学习")
        current = store.active()!!; store.distract("接电话","生活",start+3000,current.getString("version")); edit("回复电话","生活")
        check(segmentRecord(store.active()!!,1).getString("task") == "选午餐" && segmentRecord(store.active()!!,3).getString("task") == "回复电话")
        saved = store.active()!!
        // 旋转恢复必须保留原前置版本和未提交输入，不能重新捕获服务器版本。
        val draft = ActiveMetadataDraft("test",id,"captured-version",true,"已删除","未保存草稿","生活")
        check(ActiveMetadataDraft.restore(draft.json()) == draft)
    }
    Store(context,database).use { store ->
        check(canonical(store.active()!!) == canonical(saved)); store.finish(saved.getLong("started")+4000,saved.getString("version"))
        val ended = store.find("record",saved.getString("id"))!!; val pending = store.pendingCount()
        rejects { store.editActiveMetadata(saved.getString("id"),saved.getString("version"),"重新启动","") }
        check(store.active() == null && canonical(store.find("record",saved.getString("id"))!!) == canonical(ended) && store.pendingCount() == pending)
        Store(context,"active-metadata-restore.sqlite").use { restored -> restored.importBackup(store.backup()); check(content(restored.entities("record").single()) == content(ended)) }
        store.start(ended.getString("ledgerId"),"另一任务"); val other = store.active()!!
        rejects { store.editActiveMetadata(saved.getString("id"),saved.getString("version"),"误改","") }; check(canonical(store.active()!!) == canonical(other))
    }
    Store(context,"active-tag-race.sqlite").use { store ->
        val book = store.addLedger("标签并发"); store.addTag("学习"); store.addTag("生活"); store.start(book,"背单词","学习")
        val sent = store.syncRequest().getJSONArray("operations"); val ack = JSONArray(); val changes = JSONArray(); var seq=0
        sent.objects().forEach { op -> ack.put(op.getString("id")); changes.put(JSONObject().put("seq",++seq).put("kind",op.getString("kind")).put("id",op.getString("entityId")).put("heads",JSONArray().put(op.getJSONObject("entity")))) }
        store.acceptSync(JSONObject().put("serverId","tag-race").put("cursor",seq).put("ack",ack).put("changes",changes).put("more",false),sent)
        store.entities("tag").forEach { tag ->
            tag.put("deleted",true).put("version","removed-"+tag.getString("name"))
            store.acceptSync(JSONObject().put("serverId","tag-race").put("cursor",++seq).put("ack",JSONArray()).put("more",false).put("changes",JSONArray().put(JSONObject().put("seq",seq).put("kind","tag").put("id",tag.getString("id")).put("heads",JSONArray().put(tag)))),JSONArray())
        }
        val current = store.active()!!; val pending = store.pendingCount()
        rejects { store.editActiveMetadata(current.getString("id"),current.getString("version"),"更正","生活") }
        check(canonical(store.active()!!) == canonical(current) && store.pendingCount() == pending)
        store.editActiveMetadata(current.getString("id"),current.getString("version"),"保留快照","学习")
        check(store.active()!!.getString("tag") == "学习" && store.entities("tag").isEmpty())
    }
}
