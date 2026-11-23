package com.aifactory.app

import android.content.Context
import java.io.File

/**
 * 真机验证（不是"构建成功就宣布完成"）。
 *
 * 能自动拿到的证据，按可靠性排序：
 *   1. adb 可用（用户在设置页做了无线调试配对）→ 真正的 logcat 崩溃栈 + screencap 截图；
 *   2. adb 不可用 → 只能 PackageManager 拉起 + 让用户看屏幕；
 *   3. 构建失败 → 0.8.2 起日志尾部已经能定位。
 *
 * 任何情况下都写 verify.json，项目页读取它显示"已验证 / 未验证 / 崩溃"，Agent 也读它，
 * 没有 PASS 就不许自称完成。
 */
object VerifyRunner {

    data class Result(val verdict: String, val detail: String, val json: File?)

    private fun newestApk(ctx: Context, projectName: String): File? {
        val outs = RuntimePaths.outputs(ctx).listFiles()
            ?.filter { it.isFile && it.extension == "apk" && it.name.startsWith(projectName + "-") }
            ?.maxByOrNull { it.lastModified() }
        if (outs != null) return outs
        val dirs = File(RuntimePaths.workspace(ctx), projectName)
            .walkTopDown().maxDepth(8)
            .filter { it.isFile && it.extension == "apk" && it.path.contains("outputs") }
            .toList()
        return dirs.maxByOrNull { it.lastModified() }
    }

    suspend fun verify(ctx: Context, rt: RuntimeEngine, dir: File, onLine: (String) -> Unit): Result {
        val name = dir.name
        val apk = newestApk(ctx, name)
        if (apk == null) {
            val detail = "没有找到 " + name + " 的 APK。先构建，再验证。"
            return Result("NO_APK", detail, write(dir, name, "NO_APK", detail, null, null, null))
        }
        onLine("APK：" + apk.path + "（" + apk.length() + " B）")
        val pkg = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
        }.getOrNull()
        onLine("包名：" + (pkg ?: "解析失败"))

        // 1) 安装（系统安装器，需要用户在弹窗点确认；这条绕不过去，Android 不允许静默装）
        val installMsg = Installer.install(ctx, apk, pkg) { line -> onLine(line) }
        onLine("安装：" + installMsg.replace("\n", " ").take(300))
        val installed = pkg != null && runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        if (!installed) {
            val detail = "没装上（用户没确认弹窗，或签名冲突）。安装结果：" + installMsg
            return Result("NOT_INSTALLED", detail, write(dir, name, "NOT_INSTALLED", detail, apk, pkg, null))
        }

        // 2) 启动
        val launch = Launch.launch(ctx, pkg)
        onLine("启动：" + launch)
        if (!launch.startsWith("已启动")) {
            val detail = "装上了但点不开：" + launch
            return Result("NO_LAUNCHER", detail, write(dir, name, "NO_LAUNCHER", detail, apk, pkg, null))
        }

        // 3) 有 adb 才有真相：崩溃栈 + 截图判定
        val adb = AdbBridge(ctx, rt)
        val crash = if (adb.available()) {
            Thread.sleep(2500)
            val log = adb.crashLog(pkg)
            onLine("logcat(crash)：" + (if (log.isBlank()) "（空）" else log.takeLast(1200)))
            log
        } else {
            onLine("adb 未连接：拿不到 logcat 与截图（Android 不允许第三方 App 读别的进程日志）")
            ""
        }
        val shot = if (adb.available()) adb.screenshot(File(dir, "build/verify-screen.png")) else null
        val blank = if (shot != null) ImageCheck.isProbablyBlank(shot) else null
        if (shot != null) onLine("截图：" + shot.path + "，空白判定=" + blank)

        val cachedCrash = crash.contains(pkg) || crash.contains("FATAL EXCEPTION")
        val verdict = when {
            cachedCrash -> "CRASH"
            blank == true -> "BLANK"
            adb.available() -> "PASS"
            else -> "LAUNCHED_UNVERIFIED"
        }
        val detail = when (verdict) {
            "CRASH" -> "检测到崩溃栈：\n" + crash.takeLast(1500)
            "BLANK" -> "截图近乎纯色（白/黑），界面很可能没渲染出来，或应用崩了留下空窗口。"
            "PASS" -> "已启动，logcat 无本包崩溃，截图非空白。"
            else -> "已启动，但没有 adb：看不到崩溃栈与界面。装 adb 后这条才会升级为 PASS。"
        }
        return Result(verdict, detail, write(dir, name, verdict, detail, apk, pkg, shot))
    }

    private fun write(
        dir: File, name: String, verdict: String, detail: String,
        apk: File?, pkg: String?, shot: File?
    ): File? = runCatching {
        val f = File(dir, "build/verify.json")
        f.parentFile?.mkdirs()
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        f.writeText(
            "{\n" +
                "  \"project\": \"" + name + "\",\n" +
                "  \"time\": \"" + ts + "\",\n" +
                "  \"verdict\": \"" + verdict + "\",\n" +
                "  \"package\": \"" + (pkg ?: "") + "\",\n" +
                "  \"apk\": \"" + (apk?.path ?: "") + "\",\n" +
                "  \"screenshot\": \"" + (shot?.path ?: "") + "\",\n" +
                "  \"detail\": \"" + detail.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"\n" +
                "}\n"
        )
        f
    }.getOrNull()

    fun readVerdict(dir: File): String? = runCatching {
        val f = File(dir, "build/verify.json")
        if (!f.exists()) return@runCatching null
        Regex("\"verdict\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1)
    }.getOrNull()
}

object Launch {
    fun launch(ctx: Context, pkg: String?): String {
        if (pkg.isNullOrBlank()) return "没有包名，无法启动"
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "没有 LAUNCHER Activity（清单缺 MAIN/LAUNCHER intent-filter）"
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            "已启动 " + pkg
        }.getOrElse { "启动失败：" + (it.message ?: it.javaClass.simpleName) }
    }
}

/** 截图空白判定：抽样 7×7 网格，96% 以上像素同色 → 认作"没渲染出来"。 */
object ImageCheck {
    fun isProbablyBlank(png: File): Boolean = runCatching {
        if (!png.exists() || png.length() < 100) return true
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = android.graphics.BitmapFactory.decodeFile(png.path, opts) ?: return true
        val w = bmp.width; val h = bmp.height
        if (w < 8 || h < 8) return true
        var same = 0; var total = 0
        val first = bmp.getPixel(0, 0)
        for (y in 0 until 7) for (x in 0 until 7) {
            val px = bmp.getPixel((x * (w - 1) / 6), (y * (h - 1) / 6))
            total++
            if (Math.abs((px shr 16 and 0xFF) - (first shr 16 and 0xFF)) < 8 &&
                Math.abs((px shr 8 and 0xFF) - (first shr 8 and 0xFF)) < 8 &&
                Math.abs((px and 0xFF) - (first and 0xFF)) < 8
            ) same++
        }
        same.toDouble() / total > 0.96
    }.getOrDefault(true)
}
