package dev.lyushi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 独立数据库验证物理状态、历史更正和恢复。测试不读取正式资料或访问设备密钥。 */
internal fun runWaterChecks(context: Context) {
    val file = "water-check.sqlite"
    val restore = "water-restore-check.sqlite"
    val replica = "water-replica-check.sqlite"
    listOf(file,restore,replica).forEach { context.deleteDatabase(it) }
    Store(context,file).use { store ->
        val ledger=store.addLedger("饮水计时隔离")
        store.start(ledger,"持续计时")
        val active=canonical(store.active())
        store.waterSubmit("setup",JSONObject().put("capacity",750).put("ml",0))
        fun bottle()=store.waterView().getJSONObject("bottle")
        fun physical(kind: String,amount: Int) {
            val b=bottle()
            store.waterSubmit(kind,JSONObject().put("base",b.getString("version")).put("from",b.getInt("ml")).put("ml",amount))
        }
        physical("fill",600)
        check(runCatching { physical("fill",500) }.isFailure && bottle().getInt("ml")==600)
        physical("drink",450)
        physical("pour",300)
        check(waterEntries(store.waterView()).filter { countsWater(it.getString("kind")) }.sumOf { it.getInt("ml") }==150)
        check(runCatching { store.waterSubmit("capacity",JSONObject().put("base",bottle().getString("version")).put("capacity",299)) }.isFailure)
        val drink=waterEntries(store.waterView()).first { it.getString("kind")=="drink" }
        physical("undo",0)
        check(bottle().getInt("ml")==450)
        store.waterSubmit("correct",JSONObject().put("target",drink.getString("id")).put("base",drink.getString("version")).put("at",drink.getLong("at")).put("ml",125))
        val adjusted=waterEntries(store.waterView()).first { it.getString("kind")=="drink" }
        check(adjusted.getInt("ml")==125 && bottle().getInt("ml")==450)
        check(runCatching { store.waterSubmit("void",JSONObject().put("target",drink.getString("id")).put("base",drink.getString("version"))) }.isFailure)
        store.waterSubmit("void",JSONObject().put("target",adjusted.getString("id")).put("base",adjusted.getString("version")))
        check(store.waterCsv().contains("已作废") && active==canonical(store.active()))
        val backup=store.waterBackup()
        Store(context,restore).use { target ->
            target.waterImport(backup); target.waterImport(backup)
            check(waterEntries(target.waterView()).size==3 && target.waterView().getJSONObject("bottle").getInt("ml")==450)
            val bad=JSONObject(backup.toString()); bad.getJSONArray("entries").getJSONObject(0).put("ml",1.5)
            val snapshot=canonical(target.waterView())
            check(runCatching { target.waterImport(bad) }.isFailure && canonical(target.waterView())==snapshot)
        }
    }
    Store(context,file).use { check(it.active()!=null && it.waterView().getJSONObject("bottle").getInt("ml")==450) }

    // 未下载到对应页的确认不能清除队列。服务端拒绝后保存命令并移除其乐观累计。
    Store(context,replica).use { store ->
        val server="a".repeat(64)
        store.bindAccount("b".repeat(64),server)
        store.waterSubmit("setup",JSONObject().put("capacity",750).put("ml",0))
        val pending=store.waterRequest(server).getJSONArray("operations").getJSONObject(0)
        val id=pending.getString("id")
        fun response(cursor: Int,changes: JSONArray)=JSONObject().put("serverId",server).put("cursor",cursor)
            .put("more",false).put("ack",JSONObject().put(id,1)).put("changes",changes)
        store.waterAccept(server,response(0,JSONArray()))
        check(store.waterRequest(server).getJSONArray("operations").length()==1)
        val rejected=JSONObject().put("seq",1).put("command",pending).put("conflict","bottle_exists")
        store.waterAccept(server,response(1,JSONArray().put(rejected)))
        val view=store.waterView()
        check(view.optJSONObject("bottle")==null && view.getJSONArray("pending").length()==0 && view.getJSONObject("conflicts").has(id))
        check(runCatching { store.waterAccept(server,response(3,JSONArray().put(JSONObject().put("seq",3).put("command",pending)))) }.isFailure)
        check(store.waterRequest(server).getInt("cursor")==1)
    }
}
