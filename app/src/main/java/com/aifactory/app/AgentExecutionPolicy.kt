package com.aifactory.app

import android.content.Context

/**
 * User-configurable guardrails for one foreground agent execution.
 * This is a per-request budget, not a durable background worker or repair-loop engine.
 */
class AgentExecutionPolicy(context: Context) {
    private val prefs = context.getSharedPreferences("aifactory_agent_policy", Context.MODE_PRIVATE)

    val maxRounds: Int
        get() = prefs.getInt(KEY_MAX_ROUNDS, DEFAULT_ROUNDS).coerceIn(MIN_ROUNDS, HARD_MAX_ROUNDS)

    val maxToolCallsPerRound: Int = 10
    val maxRepeatedIdenticalToolCalls: Int = 3

    fun setMaxRounds(value: Int) {
        require(value in MIN_ROUNDS..HARD_MAX_ROUNDS)
        check(prefs.edit().putInt(KEY_MAX_ROUNDS, value).commit()) { "保存代理执行预算失败" }
    }

    fun recordCompletedExecution(rounds: Int, toolCalls: Int, reason: String) {
        prefs.edit()
            .putInt("last_rounds", rounds)
            .putInt("last_tool_calls", toolCalls)
            .putString("last_stop_reason", reason.take(80))
            .putLong("last_finished_at", System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val MIN_ROUNDS = 10
        const val DEFAULT_ROUNDS = 50
        const val HARD_MAX_ROUNDS = 100
        private const val KEY_MAX_ROUNDS = "max_rounds"
    }
}
