package dev.lyushi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * 网络请求在工作线程执行；默认系统证书验证且禁止重定向，不发送给其他主机。
 * 每批确认及下载数据事务成功后才继续；网络失败由稳定编号安全重试。
 */
internal fun syncBlocking(store: Store, address: String, key: String, verifyIdentity: Boolean = true): Boolean {
    val url = URL(address.trimEnd('/') + "/v1/sync")
    check(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) { "同步需要有效 HTTPS 地址" }
    check(Regex("^[0-9a-f]{64}$").matches(key)) { "设备密钥应为 64 位小写十六进制文字" }
    if (verifyIdentity) { val identity = deviceIdentity(address, key); store.bindAccount(identity.getString("userId"), identity.getString("serverId")) }
    var changed = false
    repeat(10000) {
        val request = store.syncRequest(); val sent = request.getJSONArray("operations")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            // 明确声明完整分段能力，服务器拒绝不具备该能力的旧版本写入。
            connection.setRequestProperty("X-Lyushi-Protocol", "4")
            connection.setRequestProperty("Authorization", "Bearer $key"); connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true; val bytes = canonical(request).toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            check(code == 200) { when (code) { 401 -> "设备密钥无效或已撤销"; 426 -> "服务器要求升级至 0.6.0 或更新版本"; 409 -> "同步未提交，请核对服务器身份和数据版本"; else -> "本机已保存，等待重连" } }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; check(output.size() + count <= 8 * 1024 * 1024) { "同步响应过大" }; output.write(chunk, 0, count) }
                JSONObject(output.toString("UTF-8"))
            }
            changed = store.acceptSync(response, sent) || changed
            if (!response.getBoolean("more") && store.pendingCount() == 0L) return changed
        } finally { connection.disconnect() }
    }
    error("本次同步数据较多，将在下次继续")
}

/** 前台触发时间账本与饮水轮询，各自按进度退避，并共用活动账户的串行工作线程。 */
internal class SyncClient(
    private val context: Context, private val store: Store, private val preferences: SharedPreferences,
    private val worker: ExecutorService, private val handler: Handler, private val waterUpdated: () -> Unit,
    private val updated: (Boolean,Boolean) -> Unit
) {
    private data class Connection(val address: String, val key: String, val serverId: String)

    private val profile = activeProfile(preferences)
    private var verifiedConnection: Connection? = null
    @Volatile private var nextWater = 0L
    @Volatile var waterStatus = "本机记录"; private set
    @Volatile var status = "尚未配置同步"; private set
    private val inFlight = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var nextLedger = 0L
    private var ledgerDelay = 5000L
    private var ledgerFailed = false

    fun close() { stopped = true }

    /**
     * 两条业务链路独立判定到期时间。时间账本的失败或退避不跳过到期的饮水任务，
     * 饮水失败也不改变账本的重试节奏。串行工作线程保持账户切换时数据库的关闭顺序。
     */
    fun request(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val ledgerDue = force || now >= nextLedger
        val waterDue = force || now >= nextWater
        if (stopped || (!ledgerDue && !waterDue) || !inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (stopped) return@execute
                // 两个端点都依赖同一账户身份。身份失败只处理本轮到期的业务，避免重复认证。
                val account = runCatching { connection() }
                if (ledgerDue && !stopped) syncLedger(account)
                if (waterDue && !stopped) syncWater(account)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** 未配置时保持单机模式；地址或密钥变化后必须重新校验账户绑定。 */
    private fun connection(): Connection? {
        val address = accountAddress(preferences, profile)
        val key = SecretVault(context, profileVault(profile)).read()
        if (address.isEmpty() || key.isEmpty()) {
            verifiedConnection = null
            return null
        }
        verifiedConnection?.let { if (it.address == address && it.key == key) return it }
        val identity = deviceIdentity(address, key)
        val serverId = identity.getString("serverId")
        store.bindAccount(identity.getString("userId"), serverId)
        return Connection(address, key, serverId).also { verifiedConnection = it }
    }

    /** 账本独立更新状态与重连通知，以 5 至 60 秒间隔安排重试。 */
    private fun syncLedger(account: Result<Connection?>) {
        var changed = false
        var reconnected = false
        try {
            val connection = account.getOrThrow()
            if (connection == null) {
                status = "尚未配置服务器和设备密钥"
                ledgerFailed = false
            } else {
                changed = syncBlocking(store, connection.address, connection.key, false)
                reconnected = ledgerFailed
                ledgerFailed = false
                status = "已同步"
            }
            ledgerDelay = 5000L
        } catch (error: Exception) {
            ledgerFailed = true
            ledgerDelay = minOf(60000, ledgerDelay * 2)
            status = if (error is IllegalStateException) error.message ?: "同步未完成" else "本机已保存，等待重连"
        } finally {
            nextLedger = SystemClock.elapsedRealtime() + ledgerDelay
            handler.post { if (!stopped) updated(changed, reconnected) }
        }
    }

    /** 饮水按独立结果设置状态，身份或饮水请求失败时提示本机数据仍然保留。 */
    private fun syncWater(account: Result<Connection?>) {
        var delay = 5000L
        var notify = true
        try {
            val connection = account.getOrThrow()
            if (connection == null) {
                // 单机状态未变时不通知界面重复扫描本地历史。
                notify = waterStatus != "本机记录"
                waterStatus = "本机记录"
            } else {
                syncWaterBlocking(store, connection.address, connection.key, connection.serverId)
                waterStatus = "饮水记录已同步"
            }
        } catch (error: Exception) {
            delay = 30000L
            val fallback = "饮水同步未完成，本机数据已保存"
            waterStatus = if (error is IllegalStateException) error.message ?: fallback else fallback
        } finally {
            nextWater = SystemClock.elapsedRealtime() + delay
            if (notify) handler.post { if (!stopped) waterUpdated() }
        }
    }
}

/** 水位请求复用已验证的账户身份，最多定量分页，队列由收到的回执推进。 */
internal fun syncWaterBlocking(store: Store,address: String,key: String,serverId: String) {
    val url = URL(address.trimEnd('/')+"/v1/water")
    check(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null) { "饮水同步需要 HTTPS" }
    repeat(32) {
        val request = store.waterRequest(serverId)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 7000
            connection.setRequestProperty("X-Lyushi-Water","1")
            connection.setRequestProperty("Authorization","Bearer $key")
            connection.setRequestProperty("Content-Type","application/json")
            val body = request.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            check(code == 200) { if (code == 404 || code == 426) "服务器需要启用饮水同步，本机数据已保存" else "饮水同步未完成，本机数据已保存" }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size()+count <= 1024*1024) { "饮水同步响应过大" }
                    output.write(buffer,0,count)
                }
                JSONObject(output.toString("UTF-8"))
            }
            store.waterAccept(serverId,response)
            if (!response.getBoolean("more") && store.waterRequest(serverId).getJSONArray("operations").length()==0) return
        } finally { connection.disconnect() }
    }
    error("饮水数据较多，将在下次继续同步")
}
