package com.aifactory.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「上架模式」：零 gradle 直接把项目打成 Google Play 认的 AAB。
 *
 * 链路（与宿主机实测一致）：
 *   aapt2 compile --dir res -o res.zip
 *   aapt2 link --proto-format ...            ← 必须 proto，AAB 要 resources.pb 不是 resources.arsc
 *   jar xf base.apk                          ← 拆出 proto 清单 + resources.pb + res
 *   javac → d8                               ← 产出 classes.dex
 *   jar cfM base.zip                         ← module zip（jar 产的 zip bundletool 认，已验证）
 *   bundletool build-bundle --modules=base.zip
 *   jarsigner                                ← AAB 只认 jar 签名，不是 apksigner
 *
 * 硬约束：
 *   1. targetSdk 固定 36（2026-08-31 起 Play 只收 ≥36）；
 *   2. 产物里绝不能有 .so 或运行时下发代码——AIFactory 自身走这条路必被
 *      Device and Network Abuse 拒，所以这按钮是给「它编出来的普通应用」用的；
 *   3. versionCode 必须比 Play 上已有的严格递增，否则 API 直接 400。
 */
class AabBuilder(private val ctx: Context, private val rt: RuntimeEngine) {

    data class AabResult(
        val ok: Boolean,
        val aab: File?,
        val message: String,
        val log: String,
        val versionCode: Int = 0
    )

    private val sdk = RuntimePaths.sdk(ctx)
    private val home = RuntimePaths.home(ctx)
    private val prefix = RuntimePaths.prefix(ctx)
    private val cfg = AppConfig(ctx)

    companion object {
        const val BUNDLETOOL_URL =
            "https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar"
        const val TARGET_SDK = 36
    }

    fun bundletoolFile(): File = File(home, ".aifactory/bundletool.jar")

    /** 读 build.gradle.kts 的 versionCode/versionName；没有就给 1 / "1.0"。 */
    private fun versionOf(root: File): Pair<Int, String> {
        val g = listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts")
            .map { File(root, it) }.firstOrNull { it.exists() } ?: return 1 to "1.0"
        val t = runCatching { g.readText() }.getOrDefault("")
        val vc = Regex("versionCode\\s*[=\\(]\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val vn = Regex("versionName\\s*[=\\(]?\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "1.0"
        return vc to vn
    }

    private fun pkgOf(root: File, manifestText: String): String? {
        Regex("package=\"([A-Za-z0-9_.]+)\"").find(manifestText)?.groupValues?.get(1)?.let { return it }
        val g = File(root, "app/build.gradle.kts")
        if (g.exists()) {
            Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(g.readText())?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    suspend fun build(root: File, onOutput: (String) -> Unit): AabResult {
        val bt = Packager.latestBuildTools(sdk)
            ?: return AabResult(false, null, "找不到 Android SDK build-tools（需要 aapt2 与 lib/d8.jar）。", "")
        val platform = File(sdk, "platforms").listFiles()
            ?.filter { it.isDirectory && File(it, "android.jar").exists() }
            ?.maxByOrNull { p -> p.name.split('.','-').let { s -> s.map { it.toIntOrNull() ?: 0 }.fold(0) { a, b -> a * 100 + b } } }
            ?: return AabResult(false, null, "找不到 platforms/android.jar。", "")
        val aapt2 = if (File(prefix, "bin/aapt2").exists()) File(prefix, "bin/aapt2").path
        else File(bt, "aapt2").takeIf { it.exists() }?.path
        ?: return AabResult(false, null, "找不到 aapt2。", "")
        val d8 = File(bt, "lib/d8.jar")
        if (!d8.exists()) return AabResult(false, null, "build-tools 缺 lib/d8.jar。", "")
        val lay = projectLayout(root, onOutput)
            ?: return AabResult(false, null, "找不到 java + AndroidManifest.xml 布局（app/src/main 或 src/main），无法产 AAB。", "")
        val manifest = lay.manifest
        val jarDir = bundletoolFile()
        if (!jarDir.exists()) {
            return AabResult(
                false, null,
                "缺 bundletool.jar（约 32MB）。自动下载入口：设置页 → 上架模式 → 下载 bundletool；" +
                    "或从电脑把 bundletool-all-1.18.2.jar 传进 App 的 files/home/.aifactory/ 目录并改名 bundletool.jar。",
                ""
            )
        }
        ensureKeystore()?.let { return AabResult(false, null, it, "") }

        val (vc, vn) = versionOf(root)
        val text = manifest.readText()
        val pkg = pkgOf(root, text)
        val javaBin = if (File(prefix, "bin/java").exists()) File(prefix, "bin/java").path else "java"
        val javacBin = if (File(prefix, "bin/javac").exists()) File(prefix, "bin/javac").path else "javac"
        val androidJar = File(platform, "android.jar").path
        val ks = File(home, ".aifactory/release.jks")
        val q = ::shQuote
        val work = File(root, "app/build/aab").apply { mkdirs() }
        val script = File(root, "app/.aifactory-aab.sh")

        script.writeText(buildString {
            appendLine("set -e")
            appendLine("P=" + q(root.path))
            appendLine("B=" + q(work.path))
            appendLine("AAPT2=" + q(aapt2))
            appendLine("JAR=" + q(androidJar))
            appendLine("BT=" + q(bt.path))
            appendLine("BUNDLE=" + q(jarDir.path))
            appendLine("rm -rf \$B && mkdir -p \$B/res \$B/gen \$B/classes \$B/mod/manifest \$B/mod/dex \$B/mod/res")
            // 清单缺 package 就注入：aapt2 只认 package，AGP 的 namespace 在这里无效
            appendLine("if grep -q 'package=\"' " + q(manifest.path) + "; then")
            appendLine("  cp " + q(manifest.path) + " \$B/AndroidManifest.xml")
            appendLine("else")
            val injected = pkg ?: ("com.example." + root.name.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" })
            appendLine("  sed '0,/<manifest /s//<manifest package=\"" + injected + "\" /' " + q(manifest.path) + " > \$B/AndroidManifest.xml")
            appendLine("fi")
            appendLine("echo 清单包名：\$(" + "grep -o 'package=\"[^\"]*\"' \$B/AndroidManifest.xml | head -1)")
            val srcList = lay.javaDirs.joinToString(" ") { q(it.path) }
            val resDir = lay.res
            if (resDir != null) {
                appendLine("\$AAPT2 compile --dir " + q(resDir.path) + " -o \$B/res.zip")
                appendLine("\$AAPT2 link --proto-format -o \$B/base.apk -I \$JAR --manifest \$B/AndroidManifest.xml " +
                    "--min-sdk-version 26 --target-sdk-version " + TARGET_SDK + " --version-code " + vc + " --version-name " + q(vn) +
                    " -R \$B/res.zip --java \$B/gen --auto-add-overlay")
            } else {
                appendLine("\$AAPT2 link --proto-format -o \$B/base.apk -I \$JAR --manifest \$B/AndroidManifest.xml " +
                    "--min-sdk-version 26 --target-sdk-version " + TARGET_SDK + " --version-code " + vc + " --version-name " + q(vn) +
                    " --java \$B/gen --auto-add-overlay")
            }
            appendLine("find " + srcList + " \$B/gen -name '*.java' > \$B/srcs")
            appendLine(q(javacBin) + " -source 8 -target 8 -nowarn -bootclasspath \$JAR -d \$B/classes @\$B/srcs")
            appendLine("find \$B/classes -name '*.class' > \$B/classes.lst")
            appendLine(q(javaBin) + " -Xmx768m -cp \$BT/lib/d8.jar com.android.tools.r8.D8 --lib \$JAR --min-api 26 --output \$B/mod/dex \$(" + "cat \$B/classes.lst)")
            appendLine("mkdir -p \$B/linked && cd \$B/linked && jar xf \$B/base.apk && cd \$P")
            // 必须是 proto 编码的清单；文本 xml 塞进去 bundletool 会抛 XmlNode.parseFrom
            appendLine("cp \$B/linked/AndroidManifest.xml \$B/mod/manifest/AndroidManifest.xml")
            appendLine("cp \$B/linked/resources.pb \$B/mod/resources.pb")
            appendLine("if [ -d \$B/linked/res ]; then cp -r \$B/linked/res/. \$B/mod/res/; fi")
            // assets 也要带上，否则会有资源读不到
            lay.assets?.let { a -> appendLine("mkdir -p \$B/mod/assets && cp -r " + q(a.path) + "/. \$B/mod/assets/") }
            appendLine("cd \$B/mod && jar cfM \$B/base.zip . && cd \$P")
            appendLine("mkdir -p \$P/app/build/outputs/bundle")
            appendLine(q(javaBin) + " -jar \$BUNDLE build-bundle --modules=\$B/base.zip --output=\$P/app/build/outputs/bundle/app-release.aab")
            appendLine(q(javaBin) + " -jar \$BUNDLE validate --bundle=\$P/app/build/outputs/bundle/app-release.aab | tail -4")
            appendLine("if command -v jarsigner >/dev/null 2>&1; then")
            appendLine("  jarsigner -keystore " + q(ks.path) + " -storepass:env AIF_KS_PASS -keypass:env AIF_KS_PASS " +
                "-sigalg SHA256withRSA -digestalg SHA-256 \$P/app/build/outputs/bundle/app-release.aab aifactory")
            appendLine("  echo AAB_SIGNED=1")
            appendLine("elif [ -x " + q(File(prefix, "bin/jarsigner").path) + " ]; then")
            appendLine("  " + q(File(prefix, "bin/jarsigner").path) + " -keystore " + q(ks.path) +
                " -storepass:env AIF_KS_PASS -keypass:env AIF_KS_PASS -sigalg SHA256withRSA -digestalg SHA-256 " +
                "\$P/app/build/outputs/bundle/app-release.aab aifactory")
            appendLine("  echo AAB_SIGNED=1")
            appendLine("else")
            appendLine("  echo AAB_SIGNED=0")
            appendLine("fi")
            appendLine("ls -l \$P/app/build/outputs/bundle/app-release.aab")
        })

        val t0 = System.currentTimeMillis()
        val r = rt.run(
            "sh " + q(script.path), root, 1800, mapOf("AIF_KS_PASS" to cfg.keystorePassword), onOutput
        )
        if (r.cancelled) return AabResult(false, null, "AAB 构建已取消。", r.output, vc)
        if (r.timedOut) return AabResult(false, null, "AAB 构建超时（30 分钟）。", r.output, vc)
        val aab = File(root, "app/build/outputs/bundle/app-release.aab")
        if (!r.ok || !aab.exists()) {
            return AabResult(false, null, "AAB 构建失败（退出码 ${r.exitCode}）：\n" + tailText(r.output, 2500), r.output, vc)
        }
        val signed = r.output.contains("AAB_SIGNED=1")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(RuntimePaths.outputs(ctx), root.name + "-$vc-" + stamp + ".aab")
        out.parentFile?.mkdirs()
        aab.copyTo(out, overwrite = true)
        val seconds = (System.currentTimeMillis() - t0) / 1000
        val head = "AAB 构建成功：${out.name}（${out.length() / 1024} KB，${seconds}s）\n绝对路径：${out.path}\n" +
            "包名 ${pkg ?: "?"} · versionCode $vc · versionName $vn · targetSdk $TARGET_SDK"
        return AabResult(
            true, out,
            if (signed) head + "\n已用 release.jks 做 jar 签名（jarsigner -verify 可校验）。\n" +
                "versionCode 提醒：Play 上已有 ${vc}，下一次必须大于 ${vc}。"
            else head + "\n【未签名】机器上没有 jarsigner（只有 JRE）。上传前必须签名：" +
                "电脑上执行 jarsigner -keystore release.jks app-release.aab aifactory。AAB 只认 jar 签名，apksigner 签不了。",
            r.output, vc
        )
    }

    /** 与 Packager 一致的 release keystore；不存在就生成。 */
    private suspend fun ensureKeystore(): String? {
        val ks = File(home, ".aifactory/release.jks")
        if (ks.exists()) return null
        ks.parentFile?.mkdirs()
        val r = rt.run(
            "keytool -genkeypair -keystore ${shQuote(ks.path)} -alias aifactory -keyalg RSA -keysize 2048 " +
                "-validity 36500 -storepass:env AIF_KS_PASS -keypass:env AIF_KS_PASS -dname 'CN=AIFactory, O=AIFactory'",
            home, 180, mapOf("AIF_KS_PASS" to cfg.keystorePassword)
        )
        return if (r.ok && ks.exists()) null else "创建 release.jks 失败：${tailText(r.output, 1000)}"
    }

    /**
     * 下载 bundletool。走多源：直连 GitHub → 两个加速前缀。全失败时返回明确错误，
     * 不假装成功（这是之前 0.6.x 踩过的坑：把失败埋在日志前段，UI 却显示成功）。
     */
    suspend fun downloadBundletool(onLine: (String) -> Unit): String {
        val out = bundletoolFile()
        if (out.exists() && out.length() > 10_000_000) return "已存在：" + out.path + "（" + out.length() + " B）"
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        val sources = listOf(
            BUNDLETOOL_URL,
            "https://ghfast.top/https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar",
            "https://ghproxy.net/https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar"
        )
        var last = ""
        for (u in sources) {
            onLine("尝试下载：$u")
            val r = rt.run(
                "curl -fL --retry 2 -o ${shQuote(tmp.path)} ${shQuote(u)} && mv ${shQuote(tmp.path)} ${shQuote(out.path)}",
                home, 900, emptyMap(), onLine
            )
            if (r.ok && out.exists() && out.length() > 10_000_000) {
                return "已下载：" + out.path + "（" + out.length() + " B）"
            }
            last = tailText(r.output, 600)
        }
        tmp.delete()
        return "下载失败（三个源都不通）。把 bundletool-all-1.18.2.jar 从电脑传到手机，" +
            "用文件中心导入后放到 files/home/.aifactory/bundletool.jar 即可。最后一条错误：\n$last"
    }

}
