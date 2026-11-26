package dev.lyushi.app

import org.json.JSONObject

/**
 * 活动编辑草稿与持久化记录分离；版本固定为打开表单时的值，旋转后也不得重新绑定。
 * 仅由 Activity 实例状态携带，不写入数据库、偏好或同步队列。
 */
internal data class ActiveMetadataDraft(
    val profile: String, val id: String, val version: String, val distracted: Boolean,
    val originalTag: String, var name: String, var tag: String
) {
    fun json(): String = JSONObject().put("profile", profile).put("id", id).put("version", version)
        .put("distracted", distracted).put("originalTag", originalTag).put("name", name).put("tag", tag).toString()

    /** 草稿恢复只恢复输入；是否仍能提交由当前活动编号和版本重新判定。 */
    companion object {
        fun restore(json: String): ActiveMetadataDraft = JSONObject(json).let {
            ActiveMetadataDraft(it.getString("profile"), it.getString("id"), it.getString("version"),
                it.getBoolean("distracted"), it.getString("originalTag"), it.getString("name"), it.getString("tag"))
        }
    }
}
