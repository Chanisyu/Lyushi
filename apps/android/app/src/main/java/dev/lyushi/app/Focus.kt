package dev.lyushi.app

import org.json.JSONArray
import org.json.JSONObject

/** 按服务端 JSON 的 HTML 与行分隔符转义计算大小，避免特殊字符使待同步任务超限。 */
internal fun taskPayloadSize(record: JSONObject): Int = canonical(record)
    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").toByteArray(Charsets.UTF_8).size

/** 无分段的旧记录在读取时视为一个专注区间，不修改既有版本及待上传操作。 */
internal fun taskSegments(record: JSONObject): List<JSONObject> = record.optJSONArray("segments")?.objects()
    ?: listOf(JSONObject().put("kind", "focus").put("started", record.get("started")).put("ended", record.get("ended")))

/** 原任务与分心使用同一聚合身份，当前区间决定页面状态。 */
internal fun isDistracted(record: JSONObject?): Boolean = record != null && record.isNull("ended") && taskSegments(record).last().getString("kind") == "distraction"

/** 有效时长只汇总选定版本中的专注区间，不叠加其他冲突分支。 */
internal fun focusDuration(record: JSONObject, now: Long): Long = taskSegments(record).filter { it.getString("kind") == "focus" }
    .sumOf { ((if (it.isNull("ended")) now else it.getLong("ended")) - it.getLong("started")).coerceAtLeast(0) }

/** 分心名称和标签独立保存；专注区间显示原任务统一的名称与标签。 */
internal fun segmentRecord(record: JSONObject, index: Int): JSONObject {
    val part = taskSegments(record)[index]
    return JSONObject(record.toString()).apply {
        remove("segments"); put("started", part.get("started")); put("ended", part.get("ended"))
        if (part.getString("kind") == "distraction") { put("task", part.get("task")); remove("tag"); if (part.has("tag")) put("tag", part.get("tag")) }
    }
}
