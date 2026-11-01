package dev.lyushi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在模拟器运行的存储协议检查：使用真实 SQLite，但不访问网络或读取任何设备密钥。
 * 场景覆盖远端接手、并发、分页保护、删除、备份隔离及响应失败的事务回滚。
 */
internal fun runSyncChecks(context: Context): JSONObject {
    context.deleteDatabase("protocol-check.sqlite")
    return Store(context,"protocol-check.sqlite").use { store ->
        var sequence = 0L
        fun deliver(changes: List<Pair<String,List<JSONObject>>>, sent: JSONArray = JSONArray(), more: Boolean = false): Boolean {
            val entries = JSONArray()
            changes.forEach { (kind,heads) -> entries.put(JSONObject().put("seq",++sequence).put("kind",kind).put("id",heads.first().getString("id")).put("heads",JSONArray(heads))) }
            val ack = JSONArray(); sent.objects().forEach { ack.put(it.getString("id")) }
            return store.acceptSync(JSONObject().put("serverId","local-protocol-check").put("cursor",sequence).put("ack",ack).put("changes",entries).put("more",more),sent)
        }
        val book = store.addLedger("本地协议验收")
        store.start(book,"进行中状态")
        val first = store.syncRequest().getJSONArray("operations")
        deliver(first.objects().map { it.getString("kind") to listOf(it.getJSONObject("entity")) },first)
        check(store.active()!=null && store.pendingCount()==0L)
        // 无变化的轮询应保持原有活动与同步位置，不产生新的待上传操作。
        check(!deliver(emptyList()) && store.active()!=null && store.pendingCount()==0L)

        // 别的设备结束后，本机应用同一身份的结束状态，而不是创建另一条记录。
        val ended = JSONObject(store.active()!!.toString()).put("ended",store.active()!!.getLong("started")+1000).put("version","remote-end")
        deliver(listOf("record" to listOf(ended)))
        check(store.active()==null && store.entities("record").size==1)
        val a = JSONObject(ended.toString()).put("version","branch-a").put("task","离线版本 A")
        val b = JSONObject(ended.toString()).put("version","branch-b").put("task","离线版本 B")
        deliver(listOf("record" to listOf(a,b)))
        check(store.conflicts().any { it.getJSONObject("entity").optString("task")=="离线版本 A" })
        check(store.conflicts().any { it.getJSONObject("entity").optString("task")=="离线版本 B" })

        val current = store.entities("record").single()
        check(runCatching { store.edit(current.getString("id"),book,"过期表单",current.getLong("started"),current.getLong("ended"),"stale-version") }.isFailure)
        store.edit(current.getString("id"),book,"本机未追上分页的修改",current.getLong("started"),current.getLong("ended"),current.getString("version"))
        val pending = store.syncRequest().getJSONArray("operations")
        val local = JSONObject(store.entities("record").single().toString())
        deliver(listOf("record" to listOf(a,b)),pending,true)
        check(store.entities("record").single().getString("task")=="本机未追上分页的修改")
        deliver(listOf("record" to listOf(local,b)))
        check(store.entities("record").single().getString("version")==local.getString("version"))

        // 明确删除覆盖全部已知分支；恢复旧备份只能产生档案，不能复活记录。
        val backup = store.backup()
        store.deleteRecord(local.getString("id"))
        val deletion = store.syncRequest().getJSONArray("operations")
        check(deletion.getJSONObject(0).getJSONArray("parents").length()==2)
        deliver(listOf("record" to listOf(deletion.getJSONObject(0).getJSONObject("entity"))),deletion)
        store.importBackup(backup)
        check(store.entities("record").isEmpty() && store.syncRequest().getJSONArray("operations").objects().all { it.getString("kind")=="archive" })

        val before = canonical(store.syncRequest()); val sent = store.syncRequest().getJSONArray("operations")
        val ack = JSONArray(); sent.objects().forEach { ack.put(it.getString("id")) }
        val invalid = JSONObject().put("serverId","local-protocol-check").put("cursor",sequence+1).put("ack",ack).put("changes",JSONArray()).put("more",false)
        check(runCatching { store.acceptSync(invalid,sent) }.isFailure)
        check(canonical(store.syncRequest())==before)
        invalid.put("serverId","different-server").put("cursor",sequence)
        check(runCatching { store.acceptSync(invalid,sent) }.isFailure)
        check(canonical(store.syncRequest())==before)
        // 当前协议未知字段必须在导入时拒绝，不能变成服务器永远拒绝的待传操作。
        val unsupported = JSONObject(backup.toString()); unsupported.getJSONArray("records").getJSONObject(0).put("futureField","unsupported")
        check(runCatching { store.importBackup(unsupported) }.isFailure)
        // 标签场景使用独立数据库，验证名称身份、历史快照、备份与跨页远端删除。
        context.deleteDatabase("tag-check.sqlite")
        Store(context, "tag-check.sqlite").use { tagged ->
            val ledger = tagged.addLedger("标签检查")
            tagged.addTag(" 学习 "); tagged.addTag("学习"); check(tagged.entities("tag").size == 1)
            tagged.start(ledger, "标签任务", "学习"); tagged.finish(tagged.active()!!.getLong("started") + 1000)
            tagged.deleteTag("学习"); check(tagged.entities("tag").isEmpty())
            check(tagged.entities("record").single().getString("tag") == "学习" && tagged.csv(null).contains("学习"))
            val tagBackup = tagged.backup()
            context.deleteDatabase("tag-restore-check.sqlite")
            Store(context, "tag-restore-check.sqlite").use { restored ->
                restored.importBackup(tagBackup); check(restored.entities("tag").isEmpty())
                check(restored.entities("record").single().getString("tag") == "学习")
                restored.addTag("学习"); check(restored.entities("tag").size == 1)
            }
            // 已存在的本机待传操作全部确认后，才能检查远端独立标签的变化。
            val sentTags = tagged.syncRequest().getJSONArray("operations"); val changes = JSONArray(); val ackTags = JSONArray()
            var tagSeq = 0L
            sentTags.objects().forEach { op -> ackTags.put(op.getString("id")); changes.put(JSONObject().put("seq", ++tagSeq).put("kind", op.getString("kind")).put("id", op.getString("entityId")).put("heads", JSONArray().put(op.getJSONObject("entity")))) }
            tagged.acceptSync(JSONObject().put("serverId", "tag-server").put("cursor", tagSeq).put("ack", ackTags).put("changes", changes).put("more", false), sentTags)
            val old = JSONObject(tagBackup.toString()).put("formatVersion", 1).apply { remove("tags"); getJSONArray("records").getJSONObject(0).remove("tag") }
            context.deleteDatabase("tag-old-check.sqlite")
            Store(context, "tag-old-check.sqlite").use { legacy -> legacy.importBackup(old); check(!legacy.entities("record").single().has("tag")) }
        }
        context.deleteDatabase("batch-check.sqlite")
        Store(context,"batch-check.sqlite").use { batch ->
            val batchBook = batch.addLedger("批次检查")
            val longTask = "a" + "\u0001".repeat(9999)
            batch.transaction { repeat(50) { batch.edit(null,batchBook,longTask,1700000000000,1700000001000) } }
            val request = batch.syncRequest(); val count = request.getJSONArray("operations").length()
            check(count in 1..49 && canonical(request).toByteArray(Charsets.UTF_8).size < 2*1024*1024)
        }
        JSONObject().put("passed",true).put("appVersion",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE).put("checks",JSONArray(listOf("unchanged poll","remote stop","concurrent branches","stale edit protection","pagination protection","known-branch delete","backup quarantine","transaction rollback","server identity","unknown field rejection","bounded batches","tag lifecycle and backup compatibility")))
    }
}
