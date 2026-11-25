package dev.lyushi.app

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest

/** 设备识别后的账户与服务器共同决定本地空间，旧单用户数据库不会自动成为新账户数据。 */
internal fun activeProfile(preferences: SharedPreferences): String = preferences.getString("accountProfile", "local") ?: "local"
internal fun accountDatabase(profile: String): String {
    check(profile == "local" || Regex("^[0-9a-f]{64}$").matches(profile)) { "账户数据空间无效" }
    return "profile-$profile.sqlite"
}
internal fun profileVault(profile: String) = "profile-$profile"
internal fun accountAddress(preferences: SharedPreferences, profile: String = activeProfile(preferences)): String = preferences.getString("accounts.$profile.server", "") ?: ""
internal fun accountProfileId(address: String, identity: JSONObject): String = MessageDigest.getInstance("SHA-256")
    .digest((address+"\n"+identity.getString("userId")+"\n"+identity.getString("serverId")).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 只读认证握手先于任何业务上传；仍使用系统证书验证、超时和重定向禁止规则。 */
internal fun deviceIdentity(address: String, key: String): JSONObject {
    val url = URL(address.trimEnd('/') + "/v1/device")
    check(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) { "同步需要有效 HTTPS 地址" }
    check(Regex("^[0-9a-f]{64}$").matches(key)) { "设备密钥应为 64 位小写十六进制文字" }
    val connection = url.openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false; connection.connectTimeout = 10000; connection.readTimeout = 15000
        connection.setRequestProperty("Authorization", "Bearer $key"); connection.setRequestProperty("X-Lyushi-Protocol", "4")
        check(connection.responseCode == 200) { if (connection.responseCode == 401) "设备密钥无效、已撤销或账户已停用" else "账户验证未完成，请核对服务器连接" }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val chunk=ByteArray(4096)
            while(true) { val count=input.read(chunk);if(count<0)break;check(output.size()+count<=16384) { "账户响应过大" };output.write(chunk,0,count) }
            output.toByteArray()
        }
        check(bytes.size <= 16384) { "账户响应过大" }
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { identity ->
            listOf("userId", "serverId").forEach { check(Regex("^[0-9a-f]{64}$").matches(identity.getString(it))) { "账户身份无效" } }
        }
    } finally { connection.disconnect() }
}

/** 账户与服务器绑定保存在本地元信息中，不能通过换密钥复用其他账户的上传队列。 */
internal fun Store.bindAccount(userId: String, serverId: String) = transaction {
    check(Regex("^[0-9a-f]{64}$").matches(userId) && Regex("^[0-9a-f]{64}$").matches(serverId)) { "账户身份无效" }
    val metadata = mutableMapOf<String,String>()
    readableDatabase.rawQuery("SELECT key,value FROM sync_meta WHERE key IN ('accountId','boundServerId','serverId')",null).use { cursor -> while(cursor.moveToNext()) metadata[cursor.getString(0)] = cursor.getString(1) }
    check(metadata["accountId"].isNullOrEmpty() || metadata["accountId"] == userId) { "设备密钥属于其他账户，请在设置中连接独立数据空间" }
    check(listOf("serverId","boundServerId").all { metadata[it].isNullOrEmpty() || metadata[it] == serverId }) { "服务器身份变化，请核对连接并使用独立数据空间" }
    writableDatabase.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES('accountId',?)",arrayOf(userId))
    writableDatabase.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES('boundServerId',?)",arrayOf(serverId))
}
