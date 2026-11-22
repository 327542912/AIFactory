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

    data class BuildResult(val ok: Boolean, val apk: File?, val message: String, val log: String, val pkg: String? = null)

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
        val gradleProject = projectDir != null && isGradleProject(projectDir)
        val hasWrapper = projectDir != null && File(projectDir, "gradlew").exists()
        if (gradleProject && !hasFile("bin/gradle") && !hasWrapper) {
            m += "gradle"
        } else if (gradleProject && !hasWrapper && !hasFile("opt/gradle-8.7/bin/gradle")) {
            // apt 源里的 gradle 配方是 1.9.0.0，跑不动 AGP 8.x
            m += "Gradle 8.x（当前不是 8.7，1.9 无法构建 AGP 8 工程）"
        }
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

    /** 有 settings.gradle 的是标准 Gradle 工程；没有的是「裸工程」，走不依赖网络的本地工具链。 */
    private fun isGradleProject(root: File): Boolean =
        File(root, "settings.gradle").exists() || File(root, "settings.gradle.kts").exists()

    suspend fun build(
        projectDir: File,
        release: Boolean,
        onOutput: ((String) -> Unit)? = null
    ): BuildResult {
        if (!projectDir.isDirectory) return BuildResult(false, null, "项目目录不存在：${projectDir.path}", "")
        val root = findProjectRoot(projectDir)
        // 有标准 app/ 布局的工程优先走本地工具链：不联网、几十秒出包。
        // 失败且是 Gradle 工程时才回退到 gradle（那条路径需要能访问 Google Maven）。
        val hasAppLayout = File(root, "app/src/main/AndroidManifest.xml").exists()
        if (hasAppLayout) {
            // 只走本地链。之前「失败自动回退 Gradle」会把真正的失败原因埋进日志前面，
            // 用户看到的是 gradle 的第二层错误，等于把根因藏了——这里不再回退。
            return buildPlain(root, onOutput)
        }
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
        // AGP 默认从 Maven 拉 x86_64 的 aapt2，手机上执行不了；强制用 SDK 里的 aarch64 aapt2。
        val aapt2Arg = aapt2Path()?.let { "-Pandroid.aapt2FromMavenOverride=${shQuote(it)}" } ?: ""
        val started = System.currentTimeMillis()
        val r = rt.run(
            "$gradle $task $aapt2Arg --no-daemon --console=plain --max-workers=2",
            root, 3600, mapOf("ANDROID_HOME" to sdk.path, "ANDROID_SDK_ROOT" to sdk.path), onOutput
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

    /**
     * 不依赖 Gradle、不依赖网络的构建：aapt2 → javac → D8 → 打包 → zipalign → apksigner。
     * 生成的 build.sh 与真机实测通过的那条链一致，全部用手机本地二进制。
     */
    private suspend fun buildPlain(root: File, onOutput: ((String) -> Unit)?): BuildResult {
        val bt = latestBuildTools(sdk)
            ?: return BuildResult(false, null, "找不到 Android SDK build-tools。", "")
        val platform = File(sdk, "platforms").listFiles()
            ?.filter { it.isDirectory && File(it, "android.jar").exists() }
            ?.maxWithOrNull(Comparator { a, b -> cmpVersion(a.name, b.name) })
            ?: return BuildResult(false, null, "找不到 Android SDK platforms（缺 android.jar）。", "")
        val aapt2 = aapt2Path()
            ?: return BuildResult(false, null, "找不到 aapt2。", "")
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        if (!manifest.exists()) {
            return BuildResult(false, null, "项目里没有 app/src/main/AndroidManifest.xml，无法本地编译。", "")
        }
        ensureKeystore()?.let { return BuildResult(false, null, it, "") }

        val jar = File(platform, "android.jar").path
        val javaBin = if (hasFile("bin/java")) File(prefix, "bin/java").path else "java"
        val javacBin = if (hasFile("bin/javac")) File(prefix, "bin/javac").path else "javac"
        val ks = keystoreFile()
        val d8Jar = File(bt, "lib/d8.jar")
        if (!d8Jar.exists()) return BuildResult(false, null, "build-tools 里没有 lib/d8.jar。", "")
        val signerJar = File(bt, "lib/apksigner.jar")
        if (!signerJar.exists()) return BuildResult(false, null, "build-tools 里没有 lib/apksigner.jar。", "")

        val q = ::shQuote
        val b = File(root, "app/build")
        b.mkdirs()
        val script = File(b, "aifactory-build.sh")
        script.writeText(
            buildString {
                appendLine("set -e")
                appendLine("P=" + q(root.path))
                appendLine("B=" + q(b.path))
                appendLine("AAPT2=" + q(aapt2))
                appendLine("JAR=" + q(jar))
                appendLine("BT=" + q(bt.path))
                appendLine("rm -rf \$B")
                appendLine("mkdir -p \$B/res \$B/gen \$B/classes \$B/dex")
                appendLine("if [ -d \$P/app/src/main/res ]; then")
                appendLine("  \$AAPT2 compile --dir \$P/app/src/main/res -o \$B/res")
                appendLine("  \$AAPT2 link -o \$B/base.apk -I \$JAR --manifest \$P/app/src/main/AndroidManifest.xml -R \$B/res --java \$B/gen")
                appendLine("else")
                appendLine("  \$AAPT2 link -o \$B/base.apk -I \$JAR --manifest \$P/app/src/main/AndroidManifest.xml --java \$B/gen")
                appendLine("fi")
                appendLine("find \$P/app/src/main/java \$B/gen -name \"*.java\" > \$B/srcs")
                appendLine(q(javacBin) + " -source 8 -target 8 -bootclasspath \$JAR -d \$B/classes @\$B/srcs")
                appendLine("find \$B/classes -name \"*.class\" > \$B/classes.lst")
                appendLine(q(javaBin) + " -Xmx768m -cp \$BT/lib/d8.jar com.android.tools.r8.D8 --lib \$JAR --min-api 26 --output \$B/dex \$(cat \$B/classes.lst)")
                appendLine("cp \$B/base.apk \$B/u.apk")
                appendLine("cd \$B/dex && jar uf \$B/u.apk classes.dex && cd \$P")
                appendLine("\$BT/zipalign -f 4 \$B/u.apk \$B/a.apk || cp \$B/u.apk \$B/a.apk")
                appendLine(q(javaBin) + " -jar \$BT/lib/apksigner.jar sign --min-sdk-version 26 --ks " + q(ks.path) +
                    " --ks-pass env:AIF_KS_PASS --key-pass env:AIF_KS_PASS \$B/a.apk")
                appendLine("mkdir -p \$B/outputs/apk/debug")
                appendLine("cp \$B/a.apk \$B/outputs/apk/debug/app-debug.apk")
                appendLine("ls -l \$B/outputs/apk/debug/app-debug.apk")
            }
        )
        val started = System.currentTimeMillis()
        val r = rt.run(
            "sh " + q(script.path), root, 1800, mapOf("AIF_KS_PASS" to cfg.keystorePassword), onOutput
        )
        if (r.cancelled) return BuildResult(false, null, "构建已取消。", r.output)
        if (r.timedOut) return BuildResult(false, null, "本地编译超时（30 分钟）。", r.output)
        val apk = File(b, "outputs/apk/debug/app-debug.apk")
        if (!r.ok || !apk.exists()) {
            return BuildResult(false, null, "本地编译失败（退出码 " + r.exitCode + "）：\n" + tailText(r.output, 2500), r.output)
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(RuntimePaths.outputs(ctx), root.name + "-debug-" + stamp + ".apk")
        out.parentFile?.mkdirs()
        apk.copyTo(out, overwrite = true)

        onOutput?.invoke("--- 构建后自检 ---")
        val t = SelfTest.verify(rt, out, aapt2, javaBin, signerJar)
        onOutput?.invoke(t.lines)
        if (!t.ok) {
            return BuildResult(false, null, "自检未通过，产物已丢弃：\n" + t.lines, (r.output + "\n" + t.lines), null)
        }
        return BuildResult(true, out, "构建成功并自检通过：" + out.name + "（" + out.length() / 1024 + " KB）\n" + t.lines, r.output, t.pkg)
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
    /**
     * 唤起系统安装器；返回给用户看的提示。
     * 任何异常都转成文字返回：这里抛异常会直接让 App 闪退（FileProvider 找不到匹配 root 就是这样）。
     */
    fun install(ctx: Context, apk: File, pkg: String? = null, onLog: (String) -> Unit = {}): String {
        if (!apk.exists()) return "APK 不存在：${apk.path}"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return "需要先允许 AIFactory 安装未知应用，已打开设置页。允许后请再次点击安装。"
        }
        // 统一先拷到 cache：这个目录一定在 FileProvider 授权内，不依赖 outputs 的路径匹配
        val staged = runCatching {
            val dir = File(ctx.cacheDir, "apks").apply { mkdirs() }
            File(dir, apk.name).also { apk.copyTo(it, overwrite = true) }
        }.getOrElse { return "准备安装文件失败：" + it.message }
        val r = runCatching { launch(ctx, staged) }
        if (r.isFailure) return "唤起安装器失败：" + (r.exceptionOrNull()?.message ?: "未知错误")
        if (pkg != null) watchInstall(ctx, pkg, onLog)
        return r.getOrThrow()
    }

    /**
     * 安装器是系统弹窗，我们无法确认用户点没点「安装」；
     * 所以用一个后台线程轮询包是否出现，出现就启动它——这就是「装完必须能跑起来」的自动验证。
     */
    fun watchInstall(ctx: Context, pkg: String, onLog: (String) -> Unit, seconds: Int = 90) {
        Thread {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500)
                val installed = runCatching {
                    ctx.packageManager.getPackageInfo(pkg, 0); true
                }.getOrDefault(false)
                if (installed) {
                    onLog("自检：检测到 " + pkg + " 已安装")
                    val started = runCatching {
                        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i); true
                    }.getOrDefault(false)
                    onLog(if (started) "自检：已启动 " + pkg + "，请确认界面已出现" else "自检：" + pkg + " 已安装但没有启动入口")
                    return@Thread
                }
            }
            onLog("自检：" + seconds + " 秒内未检测到 " + pkg + "（可能还没在安装器里点确认）")
        }.apply { isDaemon = true }.start()
    }

    private fun launch(ctx: Context, file: File): String {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "已唤起系统安装器，请在弹窗中确认安装。"
    }
}
