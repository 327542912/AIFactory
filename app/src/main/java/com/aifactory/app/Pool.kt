package com.aifactory.app

/**
 * 通道池：不止"主 / 备两个"，而是 N 个端点按调用次数分片 + 按失败次数冷却。
 *
 * 为什么要这么做：GLM 免费额度是"每单位时间的调用配额"，主备二元切换只会在两个端点之间来回撞墙；
 * 把请求摊到 N 个端点、并让出错的那个冷却 2～8 轮，等于手动做限流分摊（反限制的工程做法，
 * 不是绕过服务方限额——每个端点仍受自己的配额约束，只是不在一个端点上一口气撞死）。
 */
internal class PoolSlot(
    val base: String,
    val model: String,
    val key: String,
    val label: String
) {
    var calls = 0        // 本次进程内累计调用
    var ok = 0
    var fails = 0        // 连续失败，成功即清零
    var coolUntil = 0    // 冷却到第几轮（轮次 = Pool.turn）
    var lastMs = -1
    var lastCode = 0
    var lastUseTurn = -1
    /** 硬隔离：欠费 / Key 无效 / 模型不存在 / 无权限。这些不会自己好，只有手动探测成功才解除。 */
    var quarantined = false
    var qCode = 0
    var qReason = ""
}

internal object Pool {
    const val MAX_TRY_PER_TURN = 5   // 单轮最多换 5 个端点：有界，绝不无限重试
    const val BASE_COOL = 2          // 失败一次至少冷却 2 轮
    const val MAX_COOL = 8

    val slots = mutableListOf<PoolSlot>()
    var turn = 0
    /** 粘住的端点（上次成功的那个）：只要它还能用，就不为了"公平轮转"把它换掉。 */
    var preferredKey = ""
    /** 解析时被丢弃的行 / 主通道：必须让用户看见，静默丢弃等于"配了但没用"（本次故障就是这么来的） */
    val warnings = mutableListOf<String>()

    fun sync(cfg: AppConfig) {
        warnings.clear()
        val want = ArrayList<PoolSlot>()
        fun add(label: String, base: String, model: String, key: String) {
            if (model.isBlank()) { warnings += label + "：模型名为空，已跳过"; return }
            if (base.isBlank()) { warnings += label + "：Base URL 为空，已跳过"; return }
            if (!base.startsWith("http")) { warnings += label + "：Base URL 不以 http 开头（现在是 " + Secrets.scrub(base.take(24)) + "），已跳过"; return }
            val old = slots.firstOrNull { it.base == base && it.model == model }
            // label 只允许来自"名称 / 模型 / 域名"，绝不允许把任意自由文本（尤其 Key）当名字
            val safe = Secrets.scrub(label.take(16)).ifBlank { "通道" }
            want += old ?: PoolSlot(base, model, key, safe)
        }
        fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

        add("主 " + cfg.model, cfg.baseUrl, cfg.model, cfg.apiKey)
        if (cfg.hasBackup) add("备 " + cfg.backupModel, cfg.backupBaseUrl, cfg.backupModel, cfg.backupApiKey)
        var ln = 0
        for (line in cfg.extraChannels.split('\n')) {
            ln++
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) continue
            val p = t.split('|').map { it.trim() }
            // 以 URL 段为锚点解析，不再死扣段数：URL 之前是名称（可省），之后依次是模型、Key（可省=用主通道 Key）
            val urlIdx = p.indexOfFirst { it.startsWith("http") }
            if (urlIdx < 0) {
                warnings += "通道池第 " + ln + " 行没有 http(s):// 开头的地址，已跳过"
                continue
            }
            val name = p.take(urlIdx).firstOrNull { it.isNotBlank() } ?: hostOf(p[urlIdx])
            val after = p.drop(urlIdx + 1).filter { it.isNotBlank() }
            val model = after.getOrNull(0)
            if (model == null) {
                warnings += "通道池第 " + ln + " 行缺少模型名（格式：名称|https://地址|模型|Key），已跳过"
                continue
            }
            val key = after.getOrNull(1) ?: cfg.apiKey
            add(name, p[urlIdx], model, key)
        }
        slots.clear()
        slots.addAll(want)
    }

    /** 不在冷却里、且有 Key（本地 llama.cpp 允许空 Key）的端点。 */
    fun ready(): List<PoolSlot> = slots.filter { !it.quarantined && it.coolUntil <= turn }

    /** 被隔离的端点：这次请求绝不碰它们，只在设置页展示原因。 */
    fun quarantined(): List<PoolSlot> = slots.filter { it.quarantined }

    private fun keyOf(s: PoolSlot) = s.base + "|" + s.model

    fun pick(force: Boolean = false): PoolSlot? {
        val r = ready()
        if (r.isEmpty()) {
            // 只有"软冷却"的才可能被强制试一次；隔离中的（欠费/Key错/模型不存在）一次都不碰
            val soft = slots.filter { !it.quarantined }
            return if (force && soft.isNotEmpty()) soft.minByOrNull { it.coolUntil } else null
        }
        // 1) 粘住上次成功的那个：它慢一点也比换到一个没验证过、可能欠费的端点强
        val sticky = r.firstOrNull { keyOf(it) == preferredKey }
        if (sticky != null) return sticky
        // 2) 没有可粘的，就只在"验证过能跑"的里面挑，按延迟从快到慢
        val proven = r.filter { it.ok > 0 }
        if (proven.isNotEmpty()) return proven.minByOrNull { if (it.lastMs > 0) it.lastMs else Int.MAX_VALUE }
        // 3) 一个能跑的都没有，才去试没验证过的（次数少的先试，避免一直撞同一个）
        return r.minWithOrNull(compareBy({ it.calls }, { it.lastUseTurn }))
    }

    fun begin(s: PoolSlot) {
        s.calls++
        s.lastUseTurn = turn
    }

    fun success(s: PoolSlot, ms: Int) {
        s.ok++
        s.fails = 0
        s.coolUntil = 0
        s.lastMs = ms
        s.lastCode = 200
        preferredKey = keyOf(s)   // 成功后粘住它：不再为了"轮转公平"主动切换
    }

    /** 失败一次：限流类冷却 = 2 + 连续失败次数；余额/Key 类（401/402/403）直接顶格冷却，因为短时间不会自己好。 */
    fun cool(s: PoolSlot, code: Int, body: String = ""): Int {
        s.fails++
        s.lastCode = code
        if (isHardFail(code) || looksLikeModelMissing(body)) {
            // 欠费/Key错/模型不存在：不设轮次冷却，直接隔离，直到用户手动探测成功
            s.quarantined = true
            s.qCode = code
            s.qReason = reasonOf(code, body)
            if (keyOf(s) == preferredKey) preferredKey = ""   // 粘的端点坏了，解除粘滞
            return 0
        }
        val n = (BASE_COOL + s.fails).coerceAtMost(MAX_COOL)
        val isSticky = keyOf(s) == preferredKey
        // 粘住的端点遇到限流/抖动：不走"换端点"，而是原地等 1 秒再试它——切换的成本通常高于等待
        s.coolUntil = if (isSticky) turn else turn + n
        return n
    }

    /** 端点自身拒绝：换请求内容没用，必须换 Key / 充值 / 改模型名。 */
    fun isHardFail(code: Int): Boolean = code == 401 || code == 402 || code == 403

    /** 有的服务商把"模型不存在/无权限"包在 400 或 401 里，靠响应体识别。 */
    fun looksLikeModelMissing(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("model does not exist") || b.contains("invalid_model") ||
            b.contains("no permission") || b.contains("insufficient balance") || b.contains("apikey not found")
    }

    fun reasonOf(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("insufficient") || code == 402 -> "余额不足，需要充值"
            b.contains("invalid_model") || b.contains("model does not exist") -> "模型不存在/未开通"
            b.contains("apikey not found") -> "Key 不被该接口接受"
            code == 401 -> "Key 无效或无权限"
            code == 403 -> "无权限"
            code == 404 -> "地址或模型路径不对"
            else -> "端点拒绝（HTTP " + code + "）"
        }
    }

    /** 手动探测成功后解除隔离；探测失败则刷新原因，继续隔离。 */
    fun probeResult(s: PoolSlot, code: Int, body: String) {
        if (code in 200..299) {
            s.quarantined = false; s.qCode = 0; s.qReason = ""
            s.coolUntil = 0; s.fails = 0; s.lastCode = code
        } else if (isHardFail(code) || looksLikeModelMissing(body)) {
            s.quarantined = true; s.qCode = code; s.qReason = reasonOf(code, body)
        } else {
            s.coolUntil = turn + BASE_COOL; s.lastCode = code
        }
    }

    fun clearQuarantine() { for (s in slots) { s.quarantined = false; s.qCode = 0; s.qReason = "" } }

    /** 当前粘住的端点（设置页显示用）。 */
    fun preferred(): PoolSlot? = slots.firstOrNull { (it.base + "|" + it.model) == preferredKey && !it.quarantined }

    fun coolLeft(s: PoolSlot): Int = (s.coolUntil - turn).coerceAtLeast(0)

    fun reset() {
        for (s in slots) {
            s.calls = 0; s.ok = 0; s.fails = 0; s.coolUntil = 0; s.lastCode = 0
            s.quarantined = false; s.qCode = 0; s.qReason = ""
        }
        turn = 0
    }

    fun summary(): String = Secrets.scrub(slots.joinToString(" / ") {
        it.label + when {
            it.quarantined -> "（⛔" + it.qReason + "）"
            coolLeft(it) > 0 -> "（冷却 " + coolLeft(it) + " 轮）"
            else -> "（" + it.calls + " 次·成 " + it.ok + "）"
        }
    })
}
