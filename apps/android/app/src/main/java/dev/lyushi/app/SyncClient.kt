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

/** 前台五秒轮询，失败退避至一分钟；后台停止调度，尚未结束的计时仍在本机保存。 */
internal class SyncClient(
    private val context: Context, private val store: Store, private val preferences: SharedPreferences,
    private val worker: ExecutorService, private val handler: Handler, private val waterUpdated: () -> Unit,
    private val updated: (Boolean,Boolean) -> Unit
) {
    private val profile = activeProfile(preferences)
    private var verifiedConnection = ""
    private var verifiedServer = ""
    private var nextWater = 0L
    @Volatile var waterStatus = "本机记录"; private set
    @Volatile var status = "尚未配置同步"; private set
    private val inFlight = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var next = 0L
    @Volatile private var delay = 5000L
    @Volatile private var failed = false
    fun close() { stopped = true }
    fun request(force: Boolean = false) {
        if (stopped || (!force && SystemClock.elapsedRealtime() < next) || !inFlight.compareAndSet(false, true)) return
        next = SystemClock.elapsedRealtime() + delay
        worker.execute {
            var changed = false; var reconnected = false
            try {
                // 未配置时保持单机模式，不连接私人服务器。
                val address = accountAddress(preferences, profile)
                val key = SecretVault(context, profileVault(profile)).read()
                if (address.isEmpty() || key.isEmpty()) status = "尚未配置服务器和设备密钥"
                else {
                    val connection = address+"\n"+key
                    if (verifiedConnection != connection) {
                        val identity = deviceIdentity(address,key)
                        val serverId = identity.getString("serverId")
                        store.bindAccount(identity.getString("userId"),serverId)
                        verifiedServer = serverId
                        verifiedConnection = connection
                        nextWater = 0
                    }
                    changed = syncBlocking(store,address,key,false)
                    reconnected = failed; failed = false; delay = 5000; status = "已同步"
                    // 先通知时间记录更新，饮水故障单独退避，不改变时间同步结果。
                    if (changed || reconnected) {
                        val timeChanged = changed; val timeReconnected = reconnected
                        handler.post { if (!stopped) updated(timeChanged,timeReconnected) }
                        changed = false; reconnected = false
                    }
                    if (!stopped && (force || SystemClock.elapsedRealtime() >= nextWater)) {
                        val water = runCatching { syncWaterBlocking(store,address,key,verifiedServer) }
                        val failure = water.exceptionOrNull()
                        waterStatus = when (failure) {
                            null -> "饮水记录已同步"
                            is IllegalStateException -> failure.message ?: "饮水同步未完成，本机数据已保存"
                            else -> "饮水同步未完成，本机数据已保存"
                        }
                        nextWater = SystemClock.elapsedRealtime() + if (water.isSuccess) 5000 else 30000
                        handler.post { if (!stopped) waterUpdated() }
                    }
                }
            } catch (error: Exception) {
                failed = true; delay = minOf(60000, delay * 2)
                status = if (error is IllegalStateException) error.message ?: "同步未完成" else "本机已保存，等待重连"
            } finally {
                next = SystemClock.elapsedRealtime() + delay; inFlight.set(false)
                handler.post { if (!stopped) updated(changed, reconnected) }
            }
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
