package com.aifactory.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentTaskStatus {
    PLANNED, READY, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, BLOCKED
}

data class AgentTask(
    val id: String,
    val title: String,
    val description: String,
    val kind: String,
    val dependsOn: List<String>,
    val status: AgentTaskStatus,
    val result: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Durable task-plan ledger. It persists the DAG and transition history.
 * It deliberately does not execute model-proposed shell commands.
 */
class AgentTaskEngine(context: Context) {
    private val prefs = context.getSharedPreferences("aifactory_agent_tasks", Context.MODE_PRIVATE)
    private val lock = Any()

    fun tasks(): List<AgentTask> = synchronized(lock) {
        decode(prefs.getString("tasks", "[]") ?: "[]")
    }

    fun history(): List<JSONObject> = synchronized(lock) {
        val a = JSONArray(prefs.getString("history", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun createPlan(items: List<NewTask>): List<AgentTask> = synchronized(lock) {
        require(items.isNotEmpty()) { "任务计划不能为空" }
        require(items.size <= 40) { "单个计划最多 40 个任务" }
        val ids = items.map { it.id.ifBlank { UUID.randomUUID().toString() } }
        require(ids.distinct().size == ids.size) { "任务 ID 重复" }
        val known = ids.toSet()
        items.forEachIndexed { i, item ->
            require(item.title.isNotBlank() && item.title.length <= 120) { "任务标题无效" }
            require(item.description.length <= 4000) { "任务描述过长" }
            require(item.kind in setOf("scan", "read", "write", "build", "test", "review", "manual")) { "不支持的任务类型：${item.kind}" }
            require(item.dependsOn.all { it in known && it != ids[i] }) { "任务依赖引用无效" }
        }
        val planned = items.mapIndexed { i, item ->
            AgentTask(ids[i], item.title.trim(), item.description, item.kind,
                item.dependsOn.toList(), AgentTaskStatus.PLANNED)
        }
        assertAcyclic(planned)
        val old = tasks()
        require(old.none { it.status == AgentTaskStatus.RUNNING || it.status == AgentTaskStatus.WAITING_APPROVAL }) {
            "当前计划仍有活动任务，不能覆盖"
        }
        persist(planned)
        appendEvent("PLAN_CREATED", JSONObject().put("count", planned.size))
        planned
    }

    fun nextReady(): List<AgentTask> = synchronized(lock) {
        val all = tasks().toMutableList()
        val byId = all.associateBy { it.id }
        val ready = all.indices.filter { i ->
            val task = all[i]
            task.status == AgentTaskStatus.PLANNED &&
                task.dependsOn.all { byId[it]?.status == AgentTaskStatus.SUCCEEDED }
        }
        ready.forEach { i ->
            all[i] = all[i].copy(status = AgentTaskStatus.READY, updatedAt = System.currentTimeMillis())
        }
        if (ready.isNotEmpty()) {
            persist(all)
            appendEvent("TASKS_READY", JSONObject().put("count", ready.size))
        }
        ready.map { all[it] }
    }

    fun transition(id: String, next: AgentTaskStatus, result: String = ""): AgentTask = synchronized(lock) {
        val all = tasks().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        require(index >= 0) { "任务不存在" }
        val old = all[index]
        val allowed = when (old.status) {
            AgentTaskStatus.PLANNED -> setOf(AgentTaskStatus.READY, AgentTaskStatus.CANCELLED, AgentTaskStatus.BLOCKED)
            AgentTaskStatus.READY -> setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_APPROVAL, AgentTaskStatus.CANCELLED, AgentTaskStatus.BLOCKED)
            AgentTaskStatus.RUNNING -> setOf(AgentTaskStatus.SUCCEEDED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED, AgentTaskStatus.WAITING_APPROVAL)
            AgentTaskStatus.WAITING_APPROVAL -> setOf(AgentTaskStatus.READY, AgentTaskStatus.CANCELLED, AgentTaskStatus.BLOCKED)
            AgentTaskStatus.BLOCKED -> setOf(AgentTaskStatus.READY, AgentTaskStatus.CANCELLED)
            AgentTaskStatus.SUCCEEDED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED -> emptySet()
        }
        require(next in allowed) { "非法任务状态转换：${old.status} -> $next" }
        val changed = old.copy(status = next, result = result.take(12000), updatedAt = System.currentTimeMillis())
        all[index] = changed
        persist(all)
        appendEvent("TASK_${next.name}", JSONObject().put("task_id", id).put("from", old.status.name).put("result", result.take(1000)))
        changed
    }

    fun recoverInterrupted(): Int = synchronized(lock) {
        val all = tasks().toMutableList()
        var changed = 0
        all.indices.forEach { i ->
            if (all[i].status == AgentTaskStatus.RUNNING) {
                all[i] = all[i].copy(status = AgentTaskStatus.FAILED,
                    result = "应用进程中断：该任务没有可靠的执行恢复记录。请检查现场后重新运行。",
                    updatedAt = System.currentTimeMillis())
                changed++
            }
        }
        if (changed > 0) {
            persist(all)
            appendEvent("RECOVERY_MARKED_INTERRUPTED", JSONObject().put("count", changed))
        }
        changed
    }

    fun cancelPending(): Int = synchronized(lock) {
        val all = tasks().map {
            if (it.status in setOf(AgentTaskStatus.PLANNED, AgentTaskStatus.READY, AgentTaskStatus.WAITING_APPROVAL, AgentTaskStatus.BLOCKED))
                it.copy(status = AgentTaskStatus.CANCELLED, updatedAt = System.currentTimeMillis()) else it
        }
        val n = all.count { it.status == AgentTaskStatus.CANCELLED } -
            tasks().count { it.status == AgentTaskStatus.CANCELLED }
        persist(all)
        appendEvent("PENDING_CANCELLED", JSONObject().put("count", n))
        n
    }

    private fun persist(tasks: List<AgentTask>) {
        val array = JSONArray()
        tasks.forEach { t ->
            array.put(JSONObject().put("id", t.id).put("title", t.title)
                .put("description", t.description).put("kind", t.kind)
                .put("depends_on", JSONArray(t.dependsOn)).put("status", t.status.name)
                .put("result", t.result).put("created_at", t.createdAt).put("updated_at", t.updatedAt))
        }
        check(prefs.edit().putString("tasks", array.toString()).commit()) { "保存任务状态失败" }
    }

    private fun appendEvent(type: String, data: JSONObject) {
        val a = JSONArray(prefs.getString("history", "[]") ?: "[]")
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("type", type).put("data", data))
        val trimmed = JSONArray()
        val start = maxOf(0, a.length() - 500)
        for (i in start until a.length()) trimmed.put(a.getJSONObject(i))
        check(prefs.edit().putString("history", trimmed.toString()).commit()) { "保存任务历史失败" }
    }

    private fun decode(raw: String): List<AgentTask> = try {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val deps = o.optJSONArray("depends_on") ?: JSONArray()
            AgentTask(
                o.getString("id"), o.getString("title"), o.optString("description"),
                o.optString("kind", "manual"),
                (0 until deps.length()).map { deps.getString(it) },
                AgentTaskStatus.valueOf(o.optString("status", "PLANNED")),
                o.optString("result"), o.optLong("created_at"), o.optLong("updated_at")
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun assertAcyclic(tasks: List<AgentTask>) {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) error("任务依赖存在循环")
            if (id in visited) return
            visiting += id
            byId[id]?.dependsOn?.forEach(::visit)
            visiting -= id
            visited += id
        }
        tasks.forEach { visit(it.id) }
    }
}

data class NewTask(
    val title: String,
    val description: String = "",
    val kind: String = "manual",
    val dependsOn: List<String> = emptyList(),
    val id: String = ""
)
