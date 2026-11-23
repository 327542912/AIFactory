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

        val wantVer = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.versionName
        }.getOrNull()
        onLine("版本：" + (wantVer ?: "未知"))

        // 1) 安装（系统安装器需要用户点确认，这条绕不过去）+ 轮询"真的装上了"
        val before = installedStamp(ctx, pkg)
        val installMsg = Installer.install(ctx, apk, pkg) { line -> onLine(line) }
        onLine("安装：" + installMsg.replace("\n", " ").take(300))
        val installedNow = waitInstalled(ctx, pkg, wantVer, before, 90) { line -> onLine(line) }
        if (!installedNow) {
            val detail = "90 秒内没检测到新版本装上（弹窗没点确认，或签名冲突）。安装结果：" + installMsg
            return Result("NOT_INSTALLED", detail, write(dir, name, "NOT_INSTALLED", detail, apk, pkg, null))
        }
        onLine("安装确认：包 " + pkg + " 已是版本 " + (wantVer ?: "?"))

        // 2) 启动
        val launch = Launch.launch(ctx, pkg)
        onLine("启动：" + launch)
        if (!launch.startsWith("已启动")) {
            val detail = "装上了但点不开：" + launch
            return Result("NO_LAUNCHER", detail, write(dir, name, "NO_LAUNCHER", detail, apk, pkg, null))
        }
        Thread.sleep(2500)

        // 3) 无障碍在 → 真看一下界面、真动一下手
        var uiVerdict: String? = null
        var uiDetail = ""
        var shot: File? = null
        var crashLog = ""
        if (A11y.ready(ctx)) {
            val top = A11y.topPackage()
            onLine("前台包名：" + (top ?: "读不到"))
            if (top != null && pkg != null && top != pkg) {
                uiVerdict = "NO_FOREGROUND"
                uiDetail = "启动后前台是 " + (top ?: "未知") + "，不是 " + (pkg ?: "?") + "（没起来、被别的窗口盖住，或闪退回桌面）。"
            } else {
                val crash = A11y.crashSignal()
                if (crash != null) {
                    uiVerdict = "CRASH_UI"
                    uiDetail = "界面上出现崩溃/无响应弹窗：" + crash
                } else {
                    val texts = A11y.texts(30)
                    if (texts.isEmpty()) {
                        uiVerdict = "BLANK_UI"
                        uiDetail = "前台是我们这个包，但读不到任何界面文字（很可能白屏/没渲染）。"
                    } else {
                        // 真动一下手：从屏幕中部下滑，再等 1.2s 看是否仍在我们的界面
                        @Suppress("DEPRECATION")
                        val dm = ctx.resources.displayMetrics
                        val acted = A11y.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.72f, dm.widthPixels / 2f, dm.heightPixels * 0.42f, 200L)
                        Thread.sleep(1200)
                        val after = A11y.topPackage()
                        val crash2 = A11y.crashSignal()
                        if (crash2 != null) {
                            uiVerdict = "CRASH_UI"
                            uiDetail = "滑动手势之后崩溃：" + crash2
                        } else if (acted && after != null && pkg != null && after != pkg) {
                            uiVerdict = "GESTURE_LEFT"
                            uiDetail = "滑动手势后前台变成 " + after + "（手势被响应但跳走了，或崩溃回桌面）。"
                        } else {
                            uiVerdict = "PASS_UI"
                            uiDetail = "前台是 " + pkg + "，读到 " + texts.size + " 条界面文字；发了一次上滑手势，界面仍在。" +
                                "\n界面文字：" + texts.take(12).joinToString(" | ")
                        }
                    }
                }
            }
        } else {
            onLine("无障碍未开启：读不到界面文字，只能确认\"启动了\"")
        }

        // 4) 有 adb 再加一层：真崩溃栈 + 截图
        val adb = AdbBridge(ctx, rt)
        if (adb.available()) {
            crashLog = adb.crashLog(pkg ?: "")
            onLine("logcat(crash)：" + (if (crashLog.isBlank()) "（空）" else crashLog.takeLast(1200)))
            shot = adb.screenshot(File(dir, "build/verify-screen.png"))
            if (shot != null) onLine("截图：" + shot.path + "，空白判定=" + ImageCheck.isProbablyBlank(shot))
        }

        val fatal = crashLog.contains("FATAL EXCEPTION") || crashLog.contains(pkg ?: "___")
        val verdict = when {
            fatal -> "CRASH"
            uiVerdict != null -> uiVerdict
            adb.available() && shot != null && ImageCheck.isProbablyBlank(shot) -> "BLANK"
            adb.available() -> "PASS"
            else -> "LAUNCHED_UNVERIFIED"
        }
        val detail = when (verdict) {
            "CRASH" -> "logcat 里有崩溃栈：\n" + crashLog.takeLast(1500)
            "PASS_UI" -> uiDetail
            "PASS" -> "已启动，logcat 无本包崩溃，截图非空白。"
            "BLANK" -> "截图近乎纯色，界面很可能没渲染出来。"
            "LAUNCHED_UNVERIFIED" -> "已启动，但没有 adb、也没有开无障碍：看不到界面。去 设置 → 自动化验证 开启无障碍后重跑，才会给出 PASS_UI。"
            else -> uiDetail.ifBlank { "已启动，判定 " + verdict }
        }
        return Result(verdict, detail, write(dir, name, verdict, detail, apk, pkg, shot))
    }

    private fun installedStamp(ctx: Context, pkg: String?): Long = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg!!, 0).lastUpdateTime
    }.getOrDefault(-1L)

    /** 等系统把新版本装上：版本号对得上，或 lastUpdateTime 变了。 */
    private fun waitInstalled(
        ctx: Context, pkg: String?, wantVer: String?, before: Long,
        seconds: Int, onLine: (String) -> Unit
    ): Boolean {
        if (pkg == null) return false
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var lastNote = 0L
        while (System.currentTimeMillis() < deadline) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, 0)
            }.getOrNull()
            if (info != null) {
                val verOk = wantVer == null || info.versionName == wantVer
                if (verOk && info.lastUpdateTime >= before) return true
            }
            if (System.currentTimeMillis() - lastNote > 15000) {
                onLine("等待安装确认…（点了弹窗里的「安装」我会自动继续）")
                lastNote = System.currentTimeMillis()
            }
            Thread.sleep(500)
        }
        return false
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
