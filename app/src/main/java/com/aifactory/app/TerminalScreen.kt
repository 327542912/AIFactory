package com.aifactory.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 真正的终端：Termux 的 terminal-emulator（VT/xterm 状态机 + PTY JNI）与 terminal-view（Canvas 逐格渲染）。
 * 会话直接绑到 runtime 的 bash，走 forkpty，所以 isatty() 为真 —— apt 的进度、gradle 的彩色、vim/top 才有意义。
 */
class AiTerminalBridge(private val ctx: Context) : TerminalSessionClient, TerminalViewClient {

    fun setDark(dark: Boolean) {
        darkTheme = dark
        applyTheme(dark)
    }

    private fun argb(hex: Long): Int = hex.toInt()

    /** Termux 的调色板是按黑底设计的，不按主题切换会出现「白底浅字看不见」。 */
    fun applyTheme(dark: Boolean) {
        val p = TerminalColors.COLOR_SCHEME.mDefaultColors
        if (dark) {
            p[0] = argb(0xFF111111); p[1] = argb(0xFFE06C75); p[2] = argb(0xFF98C379)
            p[3] = argb(0xFFD19A66); p[4] = argb(0xFF61AFEF); p[5] = argb(0xFFC678DD)
            p[6] = argb(0xFF56B6C2); p[7] = argb(0xFFBEBEBE)
            p[8] = argb(0xFF7A7A7A); p[9] = argb(0xFFFF8A8A); p[10] = argb(0xFFB8E994)
            p[11] = argb(0xFFF2C46B); p[12] = argb(0xFF7EC8FF); p[13] = argb(0xFFE3A5F0)
            p[14] = argb(0xFF7FE3EE); p[15] = argb(0xFFFFFFFF)
            p[TextStyle.COLOR_INDEX_FOREGROUND] = argb(0xFFE6E4DF)
            p[TextStyle.COLOR_INDEX_BACKGROUND] = argb(0xFF171715)
            p[TextStyle.COLOR_INDEX_CURSOR] = argb(0xFFF2C46B)
        } else {
            p[0] = argb(0xFF1B1B1B); p[1] = argb(0xFFA52019); p[2] = argb(0xFF2E6B32)
            p[3] = argb(0xFF8A6A00); p[4] = argb(0xFF14539C); p[5] = argb(0xFF8A2E9E)
            p[6] = argb(0xFF00696E); p[7] = argb(0xFF5A5A5A)
            p[8] = argb(0xFF444444); p[9] = argb(0xFFC62828); p[10] = argb(0xFF2E7D32)
            p[11] = argb(0xFF8D6E00); p[12] = argb(0xFF1565C0); p[13] = argb(0xFF9C27B0)
            p[14] = argb(0xFF00777E); p[15] = argb(0xFF212121)
            p[TextStyle.COLOR_INDEX_FOREGROUND] = argb(0xFF1B1B1B)
            p[TextStyle.COLOR_INDEX_BACKGROUND] = argb(0xFFFFFFFF)
            p[TextStyle.COLOR_INDEX_CURSOR] = argb(0xFFB4553C)
        }
        view?.let { v ->
            v.mEmulator?.mColors?.reset()
            v.onScreenUpdated()
        }
    }

    @Volatile var ctrlDown = false
    @Volatile var altDown = false
    @Volatile private var view: TerminalView? = null
    private var session: TerminalSession? = null

    /** 标题用普通字段，不进 Compose 快照：每次输出都写状态会让整页重组，这是卡顿的主因。 */
    @Volatile var currentTitle = "bash"
        private set
    @Volatile private var darkTheme = false
    @Volatile private var finished = true

    /** 非空表示没有可用会话（例如 prefix 里的 bash 缺失），UI 直接展示这句话并给重装入口。 */
    @Volatile var problem: String? = null
        private set

    /** 标签页需要知道这条会话是不是已经退出。 */
    fun isFinished(): Boolean = finished

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
        applyTheme(darkTheme)
        v.requestFocus()
        // 新建的 TerminalView 不会自己重画已有缓冲：切页回来必须先强制刷两帧，
        // 否则屏幕是空的，要等下一次输出（按一次回车）才出现内容。
        // 卡在文本选择模式时 TerminalView 会吞掉所有输入（点屏幕也不弹键盘），
        // 表现为"终端完全没反应"。每次挂载先清掉这个状态。
        runCatching { v.stopTextSelectionMode() }
        v.post { v.onScreenUpdated() }
        v.postDelayed({ v.onScreenUpdated() }, 250)
        v.post { v.requestFocus() }
        v.postDelayed({ v.requestFocus() }, 120)
        v.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (!imm.isActive(v)) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }, 260)
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
        runCatching { RuntimeRepair.ensureUsr(ctx) }
        val bash = File(prefix, "bin/bash")
        val linkTarget = try {
            if (java.nio.file.Files.isSymbolicLink(bash.toPath()))
                java.nio.file.Files.readSymbolicLink(bash.toPath()).toString() else null
        } catch (e: Exception) { null }
        if (!bash.exists()) {
            val why = when {
                linkTarget != null -> "符号链接指向 " + linkTarget + "，该文件不存在"
                else -> "文件不存在"
            }
            problem = bash.path + "（" + why + "）。" +
                "Runtime 的 usr 树不完整，常见于 bootstrap 没装完，或被官方 termux 源（com.termux 前缀）的包替换过。" +
                "修复：设置页第 1 张卡点「选择本地 zip」，选 bootstrap-aarch64.zip 重装 Runtime。" +
                "home/ 里的项目和已装 SDK 不会丢；usr 里的 apt 包（java/gradle/git）需要重装一次。"
            finished = true
            currentTitle = "Runtime 不完整"
            return null
        }
        if (!bash.canExecute()) {
            problem = bash.path + " 存在但没有可执行权限。重装 Runtime 可修复。"
            finished = true
            currentTitle = "Runtime 不可执行"
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
        // 部分 ROM（MIUI/ColorOS）首次 showSoftInput 会被窗口焦点吞掉，补一次延迟重试
        v.postDelayed({
            if (!imm.isActive(v)) {
                v.requestFocus()
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 150)
    }

    fun send(seq: String) {
        val s = session ?: return
        val bytes = seq.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
    }

    fun pasteFromClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) return
        send(text)
    }

    fun copySelection(): Boolean {
        val v = view ?: return false
        val text = v.getSelectedText() ?: return false
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AIFactory", text))
        v.stopTextSelectionMode()
        return true
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
    override fun onTextChanged(changedSession: TerminalSession) {
        // 这是「屏幕该重画」的唯一信号：pty 回显、apt/gradle 输出都靠它触发 invalidate。
        // 不要在这里碰 Compose 状态，onScreenUpdated 只是往渲染线程 post 一次重绘。
        view?.onScreenUpdated()
    }
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
        // 之前这里遇到 isSelectingText() 就直接 return，用户点屏幕毫无反馈，看起来像终端死了。
        // 现在：先退出选择模式，再无条件弹键盘。
        runCatching { view?.stopTextSelectionMode() }
        showKeyboard()
    }

    private fun isTerminalSelecting(): Boolean = view?.isSelectingText() == true
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    // 返回 false 让 TerminalView 自己进入文本选择模式（长按出光标把手 + 复制/粘贴菜单）
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = ctrlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
}

/**
 * 多会话宿主：一个标签 = 一个独立的 AiTerminalBridge = 一条独立的 bash/PTY。
 * 之所以不在一份会话里切 view，是因为每条会话要保留自己的屏幕缓冲、环境与工作目录，
 * 共用一份反而会互相污染（cwd、环境变量、前台进程都只有一份）。
 */
class TerminalHost(private val ctx: Context) {
    private val bridges = ArrayList<AiTerminalBridge>()

    var active by mutableStateOf(0)
        private set

    init { bridges += AiTerminalBridge(ctx) }

    fun all(): List<AiTerminalBridge> = bridges
    fun count(): Int = bridges.size
    fun select(i: Int) { if (i in bridges.indices) active = i }

    fun add() {
        bridges += AiTerminalBridge(ctx)
        active = bridges.size - 1
    }

    fun close(i: Int) {
        if (i !in bridges.indices) return
        bridges[i].close()
        bridges.removeAt(i)
        if (bridges.isEmpty()) bridges += AiTerminalBridge(ctx)
        active = active.coerceIn(0, bridges.size - 1)
    }

    fun cur(): AiTerminalBridge = bridges[active.coerceIn(0, bridges.size - 1)]
}

@Composable
fun TerminalPage(host: TerminalHost, dark: Boolean = false, onGoSettings: () -> Unit = {}) {
    val active = host.active
    val bridge = host.cur()
    // 每秒轮询一次标题，避免 onTextChanged 每行输出都触发重组
    val title by produceState(initialValue = bridge.currentTitle) {
        while (true) {
            value = bridge.currentTitle
            kotlinx.coroutines.delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { host.all().forEach { it.detachView() } } }
    LaunchedEffect(dark) { host.all().forEach { it.setDark(dark) } }
    LaunchedEffect(active) { bridge.setDark(dark) }
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val problem by produceState(initialValue = bridge.problem) {
        while (true) { value = bridge.problem; kotlinx.coroutines.delay(500) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "AIFactory Runtime",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.3.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Muted)
            }
            TextButton(onClick = { bridge.restart() }) { Text("重开会话", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            TextButton(onClick = { bridge.pasteFromClipboard() }) { Text("粘贴", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 标签栏：切标签、关标签、新建。标签各自独立，互不干扰。
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            host.all().forEachIndexed { i, b ->
                val sel = i == active
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { host.select(i) }
                        .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    if (sel) Text("●", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        (if (b.isFinished()) "已退出 " else "bash ") + (i + 1),
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (b.isFinished()) Color(0xFFB4553C)
                        else if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .75f)
                    )
                    if (host.count() > 1) {
                        TextButton(
                            onClick = { host.close(i) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(26.dp)
                        ) { Text("×", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = if (sel) MaterialTheme.colorScheme.onSurface else Muted) }
                    }
                }
            }
          }
          TextButton(onClick = { host.add() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
              Text("+ 新标签", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(active) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c: Context ->
                        TerminalView(c, null).also { v -> bridge.attachView(v) }
                    }
                )
            }
            if (problem != null) {
                Column(
                    Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("终端会话没有启动", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB4553C))
                    Text(
                        "因此屏幕上没有提示符，也没有可输入的行，键盘打字不会有任何反应。",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(problem ?: "", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 10.dp)) {
                        TextButton(onClick = onGoSettings) { Text("去设置重装 Runtime", fontSize = 12.sp) }
                        TextButton(onClick = { bridge.restart() }) { Text("重试", fontSize = 12.sp) }
                    }
                }
            }
        }

        if (showInput) AlertDialog(
            onDismissRequest = { showInput = false },
            title = { Text("输入到终端") },
            text = {
                Column {
                    Text("软键盘不弹时用这个：直接写进当前 bash，回车即执行。", fontSize = 11.sp, color = Muted)
                    OutlinedTextField(
                        inputText, { inputText = it }, singleLine = false,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("例如 ls -la") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = inputText
                    showInput = false
                    inputText = ""
                    if (t.isNotBlank()) { bridge.send(t); bridge.send("\r") }
                }) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { showInput = false }) { Text("取消") } }
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
            termKey("输入行") { showInput = true }
        }
    }
}

@Composable
private fun termKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 12.sp, color = if (active) Accent else Color(0xFF6E6A63), fontWeight = FontWeight.Medium)
    }
}
