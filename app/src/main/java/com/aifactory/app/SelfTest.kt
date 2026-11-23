package com.aifactory.app

import java.io.File
import java.util.zip.ZipFile

/**
 * 构建后的自动自检：不通过就不算成功。
 * 每一条都对应一个真实会发生的失败，比如「manifest 缺 package」「没有 launcher activity」
 * 「没签名」「minSdk 超出 targetSdk 28 允许的 execve 范围」。
 */
object SelfTest {

    /** 自检失败时把 badging 里所有 *dkVersion 行原样贴出来，省得下次再猜字段名。 */
    private fun tailBadging(out: String): String =
        out.lineSequence().filter { it.contains("dkVersion") }.joinToString(" | ").ifBlank { "(无)" }

    data class Report(val ok: Boolean, val pkg: String?, val launch: String?, val lines: String)

    suspend fun verify(
        rt: RuntimeEngine,
        apk: File,
        aapt2: String,
        javaBin: String,
        signerJar: File
    ): Report {
        val sb = StringBuilder()
        var ok = true
        fun mark(cond: Boolean, msg: String) {
            sb.appendLine((if (cond) "[通过] " else "[失败] ") + msg)
            if (!cond) ok = false
        }

        val entries = runCatching {
            ZipFile(apk).use { z -> z.entries().toList().map { it.name } }
        }.getOrNull() ?: emptyList()

        mark(entries.contains("AndroidManifest.xml"), "APK 含 AndroidManifest.xml")
        mark(entries.contains("classes.dex"), "APK 含 classes.dex（否则装上也打不开）")
        mark(entries.any { it == "resources.arsc" }, "APK 含 resources.arsc")
        mark(entries.none { it.startsWith("META-INF/") && it.endsWith(".RSA") } == false,
            "存在 v1 签名块（META-INF）")

        val v = rt.run(
            "$javaBin -jar ${shQuote(signerJar.path)} verify -v --min-sdk-version 21 ${shQuote(apk.path)}",
            apk.parentFile ?: apk, 120, emptyMap(), null
        )
        mark(v.output.contains("Verifies"), "apksigner verify 通过签名校验")
        mark(v.output.contains("v2 scheme (APK Signature Scheme v2): true"), "含 v2 签名方案")

        val b = rt.run(
            "$aapt2 dump badging ${shQuote(apk.path)}",
            apk.parentFile ?: apk, 120, emptyMap(), null
        )
        val pkg = Regex("package: name='([^']+)'").find(b.output)?.groupValues?.get(1)
        val launch = Regex("launchable-activity: name='([^']+)'").find(b.output)?.groupValues?.get(1)
        // aapt2 不同版本打的行名不一样：新的是 minSdkVersion:'26'，老的是 sdkVersion:'26'。
        // 只认 sdkVersion 会让 min 永远“未声明”——0.6.1 到 0.9.5 一直栽在这。
        val minSdk = Regex("minSdkVersion:'(\\d+)'").find(b.output)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?m)^sdkVersion:'(\\d+)'").find(b.output)?.groupValues?.get(1)?.toIntOrNull()
        val targetSdk = Regex("targetSdkVersion:'(\\d+)'").find(b.output)?.groupValues?.get(1)?.toIntOrNull()

        mark(pkg != null, "解析到包名：" + (pkg ?: "未解析到"))
        mark(launch != null, "声明了可启动 Activity：" + (launch ?: "没有 LAUNCHER"))
        mark(minSdk != null && minSdk >= 21, "minSdkVersion=" + (minSdk ?: "未声明") + "（至少要 21）" +
            (if (minSdk == null) "  [badging 里没有 minSdkVersion 行，原始：" + tailBadging(b.output) + "]" else ""))
        mark(targetSdk == null || targetSdk <= 28,
            "targetSdkVersion=" + (targetSdk ?: "未声明") + "（必须 ≤ 28，否则 execve 私有目录 ELF 会被 SELinux 拒绝）")

        sb.appendLine("包名=" + (pkg ?: "?") + "  入口=" + (launch ?: "?") + "  体积=" + apk.length() + " B")
        return Report(ok, pkg, launch, sb.toString())
    }
}
