package com.aifactory.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 真正的终端：Termux 的 terminal-emulator（VT/xterm 状态机 + PTY JNI）与 terminal-view（Canvas 逐格渲染）。
 * 会话直接绑到 runtime 的 bash，走 forkpty，所以 isatty() 为真 —— apt 的进度、gradle 的彩色、vim/top 才有意义。
 */
class AiTerminalBridge(private val ctx: Context) : TerminalSessionClient, TerminalViewClient {

    @Volatile var ctrlDown = false
    @Volatile var altDown = false
    @Volatile private var view: TerminalView? = null
    private var session: TerminalSession? = null

    /** 标题用普通字段，不进 Compose 快照：每次输出都写状态会让整页重组，这是卡顿的主因。 */
    @Volatile var currentTitle = "bash"
        private set
    @Volatile private var finished = true

    /** 非空表示没有可用会话（例如 prefix 里的 bash 缺失），UI 直接展示这句话并给重装入口。 */
    @Volatile var problem: String? = null
        private set

    private val scaledDensity = ctx.resources.displayMetrics.scaledDensity
    private var fontPx = 13f * scaledDensity
    private var scaleAcc = 1f

    fun attachView(v: TerminalView) {
        view = v
        v.setTerminalViewClient(this)
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.setTextSize(fontPx.toInt())
        val s = session?.takeIf { !finished } ?: startSession()
        if (s == null) return
        v.attachSession(s)
        v.requestFocus()
        v.post { v.requestFocus() }
    }

    /** 离开终端页只解绑视图，bash 会话继续活着，回来时 attachSession 会把整屏缓冲重新画出来。 */
    fun detachView() {
        view = null
    }

    /** 双指缩放：只改字号并让 TerminalView 重算行列，进而通过 JNI 给 PTY 发 SIGWINCH。 */
    fun zoomBy(step: Float) {
        val v = view ?: return
        fontPx = (fontPx * step).coerceIn(9f * scaledDensity, 26f * scaledDensity)
        v.setTextSize(fontPx.toInt())
    }

    private fun startSession(): TerminalSession? {
        val files = ctx.filesDir
        val prefix = File(files, "usr")
        val home = File(files, "home")
        val ws = File(home, "projects").apply { mkdirs() }
        val tmp = File(prefix, "tmp").apply { mkdirs() }
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            problem = "找不到 " + bash.path + "。Runtime 不完整（可能是 bootstrap 只装了一半，或被官方 termux 源污染的包替换过）。到「设置」第 1 张卡点「选择本地 zip」，选 bootstrap-aarch64.zip 重装 Runtime；home/ 里的项目和 SDK 不会丢，usr 里的 apt 包需要重装。"
            finished = true
            currentTitle = "Runtime 不完整"
            return null
        }
        problem = null
        val env = arrayOf(
            "HOME=" + home.path,
            "PREFIX=" + prefix.path,
            "PATH=" + prefix.path + "/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=" + prefix.path + "/lib",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TMPDIR=" + tmp.path,
            "SHELL=" + bash.path
        )
        val s = TerminalSession(bash.path, ws.path, arrayOf("-l"), env, 3000, this)
        session = s
        finished = false
        currentTitle = "bash"
        return s
    }

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    fun send(seq: String) {
        val s = session ?: return
        val bytes = seq.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
    }

    fun pasteFromClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (!text.isNullOrEmpty()) send(text)
    }

    fun restart() {
        close()
        val v = view ?: return
        startSession()?.let { v.attachSession(it) }
    }

    fun close() {
        try { session?.finishIfRunning() } catch (_: Exception) {}
        session = null
        finished = true
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {
        val t = changedSession.title
        if (!t.isNullOrBlank()) currentTitle = t
    }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        finished = true
        currentTitle = "会话已退出，点「重开会话」"
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AIFactory", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) = pasteFromClipboard()
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = 0 // 0 = block 光标，与 Termux 默认一致
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    // ---- TerminalViewClient ----
    override fun onScale(scale: Float): Float {
        // TerminalView 把累计缩放因子交进来；这里自己攒，跨过阈值才动字号，跨完清零
        scaleAcc *= scale
        if (scaleAcc >= 1.1f) { zoomBy(1.1f); scaleAcc = 1f }
        else if (scaleAcc <= 0.9f) { zoomBy(1f / 1.1f); scaleAcc = 1f }
        return 1.0f
    }
    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = true
    override fun readControlKey(): Boolean = ctrlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
}

@Composable
fun TerminalPage(bridge: AiTerminalBridge, onGoSettings: () -> Unit = {}) {
    // 每秒轮询一次标题，避免 onTextChanged 每行输出都触发重组
    val title by produceState(initialValue = bridge.currentTitle) {
        while (true) {
            value = bridge.currentTitle
            kotlinx.coroutines.delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { bridge.detachView() } }
    val problem by produceState(initialValue = bridge.problem) {
        while (true) { value = bridge.problem; kotlinx.coroutines.delay(500) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("AIFactory Runtime", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(title, fontSize = 10.sp, color = Muted)
            }
            TextButton(onClick = { bridge.restart() }) { Text("重开会话", fontSize = 12.sp) }
            TextButton(onClick = { bridge.pasteFromClipboard() }) { Text("粘贴", fontSize = 12.sp) }
        }

        problem?.let { msg ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(msg, fontSize = 12.sp, color = Color(0xFFB4553C))
                Row(Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = onGoSettings) { Text("去设置重装 Runtime", fontSize = 12.sp) }
                    TextButton(onClick = { bridge.restart() }) { Text("已重装，重试", fontSize = 12.sp) }
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c: Context ->
                TerminalView(c, null).also { v -> bridge.attachView(v) }
            }
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            termKey("ESC") { bridge.send("\u001b") }
            termKey("TAB") { bridge.send("\t") }
            termKey("CTRL", active = bridge.ctrlDown) { bridge.ctrlDown = !bridge.ctrlDown }
            termKey("ALT", active = bridge.altDown) { bridge.altDown = !bridge.altDown }
            termKey("←") { bridge.send("\u001b[D") }
            termKey("↑") { bridge.send("\u001b[A") }
            termKey("↓") { bridge.send("\u001b[B") }
            termKey("→") { bridge.send("\u001b[C") }
            termKey("|") { bridge.send("|") }
            termKey("-") { bridge.send("-") }
            termKey("~") { bridge.send("~") }
            termKey("/") { bridge.send("/") }
            termKey("HOME") { bridge.send("\u001b[H") }
            termKey("END") { bridge.send("\u001b[F") }
            termKey("键盘") { bridge.showKeyboard() }
        }
    }
}

@Composable
private fun termKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 12.sp, color = if (active) Accent else Color(0xFF6E6A63), fontWeight = FontWeight.Medium)
    }
}
