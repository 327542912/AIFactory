package com.aifactory.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 构建 → 签名 → 输出 APK。全部在应用内 Runtime 里完成。 */
class Packager(private val ctx: Context, private val rt: RuntimeEngine) {

    private val prefix = RuntimePaths.prefix(ctx)
    private val home = RuntimePaths.home(ctx)
    private val sdk = RuntimePaths.sdk(ctx)
    private val cfg = AppConfig(ctx)

    data class BuildResult(val ok: Boolean, val apk: File?, val message: String, val log: String)

    companion object {
        fun latestBuildTools(sdk: File): File? =
            File(sdk, "build-tools").listFiles()?.filter { it.isDirectory }
                ?.maxWithOrNull(Comparator { a, b -> cmpVersion(a.name, b.name) })

        private fun cmpVersion(a: String, b: String): Int {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }

    private fun hasFile(rel: String) = File(prefix, rel).exists()

    private fun aapt2Path(): String? {
        if (hasFile("bin/aapt2")) return File(prefix, "bin/aapt2").path
        val bt = latestBuildTools(sdk) ?: return null
        return File(bt, "aapt2").takeIf { it.exists() }?.path
    }

    private fun apksignerCommand(): String? {
        if (hasFile("bin/apksigner")) return shQuote(File(prefix, "bin/apksigner").path)
        val bt = latestBuildTools(sdk) ?: return null
        val jar = File(bt, "lib/apksigner.jar")
        return if (jar.exists()) "java -jar ${shQuote(jar.path)}" else null
    }

    /** 返回缺失的工具；空列表表示可以构建。 */
    fun missingTools(projectDir: File? = null): List<String> {
        val m = ArrayList<String>()
        if (!hasFile("bin/java") && !hasFile("opt/openjdk/bin/java")) m += "JDK（java）"
        val hasWrapper = projectDir != null && File(projectDir, "gradlew").exists()
        if (!hasFile("bin/gradle") && !hasWrapper) m += "gradle"
        if (!File(sdk, "platforms").isDirectory) m += "Android SDK（platforms）"
        if (aapt2Path() == null) m += "aapt2"
        return m
    }

    fun toolchainReport(): String = rt.statusReport()

    /** 项目可能被压缩包多包了一层目录：向下找一层含 settings.gradle 的目录。 */
    fun findProjectRoot(dir: File): File {
        fun isRoot(d: File) = listOf("settings.gradle", "settings.gradle.kts").any { File(d, it).exists() }
        if (isRoot(dir)) return dir
        val kids = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        return kids.firstOrNull { isRoot(it) } ?: dir
    }

    private fun ensureGradleProps() {
        val f = File(home, ".gradle/gradle.properties")
        f.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("# managed by AIFactory")
        sb.appendLine("org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8")
        sb.appendLine("org.gradle.daemon=false")
        aapt2Path()?.let { sb.appendLine("android.aapt2FromMavenOverride=$it") }
        f.writeText(sb.toString())
    }

    private fun writeLocalProperties(root: File) {
        val f = File(root, "local.properties")
        val keep = if (f.exists()) f.readLines().filter { !it.startsWith("sdk.dir") } else emptyList()
        f.writeText((keep + "sdk.dir=${sdk.path}").joinToString("\n") + "\n")
    }

    suspend fun build(
        projectDir: File,
        release: Boolean,
        onOutput: ((String) -> Unit)? = null
    ): BuildResult {
        if (!projectDir.isDirectory) return BuildResult(false, null, "项目目录不存在：${projectDir.path}", "")
        val root = findProjectRoot(projectDir)
        val missing = missingTools(root)
        if (missing.isNotEmpty()) {
            return BuildResult(
                false, null,
                "缺少工具链：${missing.joinToString("、")}。请到 设置 → Runtime 安装 Runtime（含 JDK/Gradle/aapt2）与 Android SDK。", ""
            )
        }
        ensureGradleProps()
        writeLocalProperties(root)

        val gradle = if (hasFile("bin/gradle")) "gradle" else "sh ./gradlew"
        val task = if (release) "assembleRelease" else "assembleDebug"
        val started = System.currentTimeMillis()
        val r = rt.run(
            "$gradle $task --no-daemon --console=plain --max-workers=2",
            root, 3600, emptyMap(), onOutput
        )
        if (r.cancelled) return BuildResult(false, null, "构建已取消。", r.output)
        if (r.timedOut) return BuildResult(false, null, "构建超时（60 分钟）。", r.output)
        if (!r.ok) return BuildResult(false, null, "构建失败（退出码 ${r.exitCode}）。", r.output)

        val variant = if (release) "release" else "debug"
        val apks = root.walkTopDown().maxDepth(9)
            .filter { it.isFile && it.extension == "apk" && it.path.contains("outputs${File.separator}apk") }
            .filter { it.lastModified() >= started - 2000 }
            .toList()
        val apk = apks.filter { it.path.contains("${File.separator}$variant${File.separator}") }
            .maxByOrNull { it.lastModified() } ?: apks.maxByOrNull { it.lastModified() }
            ?: return BuildResult(false, null, "构建成功，但没有找到新生成的 APK。", r.output)

        var finalApk = apk
        if (apk.name.contains("unsigned")) {
            val signed = File(apk.parentFile, apk.name.replace("unsigned", "signed"))
            val err = sign(apk, signed)
            if (err != null) return BuildResult(false, null, err, r.output)
            finalApk = signed
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(RuntimePaths.outputs(ctx), "${root.name}-$variant-$stamp.apk")
        out.parentFile?.mkdirs()
        finalApk.copyTo(out, overwrite = true)
        return BuildResult(true, out, "构建成功：${out.name}（${out.length() / 1024} KB）", r.output)
    }

    private fun keystoreFile() = File(home, ".aifactory/release.jks")

    private suspend fun ensureKeystore(): String? {
        val ks = keystoreFile()
        if (ks.exists()) return null
        ks.parentFile?.mkdirs()
        val r = rt.run(
            "keytool -genkeypair -keystore ${shQuote(ks.path)} -alias aifactory -keyalg RSA -keysize 2048 " +
                "-validity 36500 -storepass:env AIF_KS_PASS -keypass:env AIF_KS_PASS " +
                "-dname 'CN=AIFactory, O=AIFactory'",
            home, 180, mapOf("AIF_KS_PASS" to cfg.keystorePassword)
        )
        return if (r.ok && ks.exists()) null else "创建签名密钥失败：${tailText(r.output, 1200)}"
    }

    /** 成功返回 null，失败返回错误信息。 */
    suspend fun sign(unsigned: File, out: File): String? {
        ensureKeystore()?.let { return it }
        val signer = apksignerCommand() ?: return "找不到 apksigner，请安装 Runtime 中的 apksigner 或 SDK build-tools。"
        val r = rt.run(
            "$signer sign --ks ${shQuote(keystoreFile().path)} --ks-key-alias aifactory " +
                "--ks-pass env:AIF_KS_PASS --key-pass env:AIF_KS_PASS " +
                "--out ${shQuote(out.path)} ${shQuote(unsigned.path)}",
            home, 300, mapOf("AIF_KS_PASS" to cfg.keystorePassword)
        )
        return if (r.ok && out.exists()) null else "APK 签名失败：${tailText(r.output, 1200)}"
    }
}

object Installer {
    /** 唤起系统安装器；返回给用户看的提示。 */
    fun install(ctx: Context, apk: File): String {
        if (!apk.exists()) return "APK 不存在：${apk.path}"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return "需要先允许 AIFactory 安装未知应用，已打开设置页。允许后请再次点击安装。"
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "已唤起系统安装器，请在弹窗中确认安装。"
    }
}
