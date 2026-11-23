package com.aifactory.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：让"构建完 → 装完 → 自动验证"这条链有眼睛和手。
 *  眼睛 = rootInActiveWindow 读前台应用的界面文字（崩溃弹窗、白屏、包名是否对）
 *  手   = dispatchGesture 发点按/滑动，验证界面能响应
 * 没开无障碍时一切退化成"已启动、未验证"，绝不假报 PASS。
 */
class VerifyA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        inst = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var inst: VerifyA11yService? = null
    }
}

object A11y {

    fun enabled(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
    }.getOrDefault(false)

    /** 开关打开且服务已被系统连上，才算真的能用。 */
    fun ready(ctx: Context): Boolean = enabled(ctx) && VerifyA11yService.inst != null

    fun openSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun topPackage(): String? = runCatching {
        VerifyA11yService.inst?.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    /** 前台窗口里所有可见文字（text + contentDescription），最多 limit 条。 */
    fun texts(limit: Int = 60): List<String> = runCatching {
        val root = VerifyA11yService.inst?.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        walk(root, out, limit)
        out
    }.getOrDefault(emptyList())

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<String>, limit: Int) {
        if (n == null || out.size >= limit) return
        val t = n.text?.toString()?.trim()
        val d = n.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add(t)
        else if (!d.isNullOrEmpty()) out.add(d)
        for (i in 0 until n.childCount) {
            if (out.size >= limit) return
            walk(n.getChild(i), out, limit)
        }
    }

    private val CRASH_WORDS = listOf(
        "已停止运行", "屡次停止", "无响应", "响应超时", "很抱歉", "已停止",
        "has stopped", "isn't responding", "keeps stopping", "Application Error"
    )

    fun crashSignal(): String? = texts(120).firstOrNull { line ->
        CRASH_WORDS.any { w -> line.contains(w, ignoreCase = true) }
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 60L)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 180L): Boolean =
        gesture(x1, y1, x2, y2, ms)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean = runCatching {
        val svc = VerifyA11yService.inst ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            if (x2 != x1 || y2 != y1) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, ms.coerceAtLeast(50L))
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }.getOrDefault(false)
}
