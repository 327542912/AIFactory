package com.aifactory.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingFileChange(
    val id: String,
    val projectId: String,
    val path: String,
    val content: String,
    val reason: String,
    val createdAt: Long
)

/** Stores model-proposed edits separately until a person explicitly applies them. */
class PendingChangeStore(context: Context) {
    private val prefs = context.getSharedPreferences("aifactory_pending_changes", Context.MODE_PRIVATE)
    private val key = "items"

    @Synchronized
    fun list(): List<PendingFileChange> {
        val a = JSONArray(prefs.getString(key, "[]") ?: "[]")
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            PendingFileChange(
                o.optString("id"), o.optString("project_id"), o.optString("path"),
                o.optString("content"), o.optString("reason"), o.optLong("created_at")
            )
        }
    }

    @Synchronized
    fun add(projectId: String, path: String, content: String, reason: String): PendingFileChange {
        require(path.isNotBlank() && path.length <= 400) { "文件路径无效" }
        require(content.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "单文件建议不能超过 2 MB" }
        require(reason.length <= 1000) { "修改说明过长" }
        val current = list().toMutableList()
        require(current.size < 30) { "待审批修改已达到 30 项上限" }
        val item = PendingFileChange(UUID.randomUUID().toString(), projectId, path,
            content, reason, System.currentTimeMillis())
        current += item
        persist(current)
        return item
    }

    @Synchronized
    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    private fun persist(items: List<PendingFileChange>) {
        val a = JSONArray()
        items.forEach { item ->
            a.put(JSONObject().put("id", item.id).put("project_id", item.projectId)
                .put("path", item.path).put("content", item.content)
                .put("reason", item.reason).put("created_at", item.createdAt))
        }
        check(prefs.edit().putString(key, a.toString()).commit()) { "保存待审批修改失败" }
    }
}
