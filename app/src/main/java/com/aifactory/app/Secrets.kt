package com.aifactory.app

/**
 * 密钥脱敏。屏幕上、聊天记录里、日志里都不许出现完整 Key。
 *
 * 之前通道池把 Key 当成"通道名"显示出来（行格式写错时 label 取了不该取的那一段），
 * 等于把密钥印在截图上。这里做三层防护：
 *  1) 组 label 的地方绝不取任意自由文本，只用"名称|模型|域名"三选一；
 *  2) 所有准备显示/落盘的文字过 scrub()，把任何形如 Key 的串替换成 sk-****abcd；
 *  3) 加载旧会话时同样过 scrub()，历史里已经泄露的那段也会被遮掉。
 */
internal object Secrets {
    private val API_LIKE = Regex("""(sk|ak|pk|gsk|or|xai|hf|ghp|github_pat|AIza)[-_A-Za-z0-9]{12,}""")
    private val BEARER = Regex("""(Bearer\s+)([A-Za-z0-9._\-]{12,})""", RegexOption.IGNORE_CASE)
    private val QUERY_KEY = Regex("""(key|api_key|apikey|token)=([A-Za-z0-9._\-]{12,})""", RegexOption.IGNORE_CASE)

    fun mask(s: String): String {
        if (s.length <= 8) return "****"
        return s.take(3) + "****" + s.takeLast(4)
    }

    /** 把一段文字里所有像 Key 的东西遮掉；known 里是当前配置中的真实 Key（精确命中优先）。 */
    fun scrub(text: String, known: List<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var out = text
        for (k in known) if (k.length >= 8 && out.contains(k)) out = out.replace(k, mask(k))
        out = BEARER.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        out = QUERY_KEY.replace(out) { m -> m.groupValues[1] + "=" + mask(m.groupValues[2]) }
        out = API_LIKE.replace(out) { m -> mask(m.value) }
        return out
    }

    /** 从配置里收集真实 Key，用于精确替换（比正则更保险）。 */
    fun knownKeys(cfg: AppConfig): List<String> = buildList {
        if (cfg.apiKey.isNotBlank()) add(cfg.apiKey)
        if (cfg.backupApiKey.isNotBlank()) add(cfg.backupApiKey)
        for (line in cfg.extraChannels.split('\n')) {
            val p = line.split('|').map { it.trim() }
            for (t in p) if (t.length >= 12 && !t.startsWith("http")) add(t)
        }
    }.distinct()
}
