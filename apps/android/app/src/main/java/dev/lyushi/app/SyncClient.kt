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
internal fun syncBlocking(store: Store, address: String, key: String): Boolean {
    val url = URL(address.trimEnd('/') + "/v1/sync")
    check(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) { "同步需要有效 HTTPS 地址" }
    check(Regex("^[0-9a-f]{64}$").matches(key)) { "设备密钥应为 64 位小写十六进制文字" }
    var changed = false
    repeat(10000) {
        val request = store.syncRequest(); val sent = request.getJSONArray("operations")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer $key"); connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true; val bytes = canonical(request).toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            check(code == 200) { when (code) { 401 -> "设备密钥无效或已撤销"; 409 -> "同步未提交，请核对服务器身份和数据版本"; else -> "本机已保存，等待重连" } }
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
    private val worker: ExecutorService, private val handler: Handler, private val updated: (Boolean,Boolean) -> Unit
) {
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
                val address = preferences.getString("server", "") ?: ""
                val key = SecretVault(context).read()
                if (address.isEmpty() || key.isEmpty()) status = "尚未配置服务器和设备密钥"
                else { changed = syncBlocking(store, address, key); reconnected = failed; failed = false; delay = 5000; status = "已同步" }
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
