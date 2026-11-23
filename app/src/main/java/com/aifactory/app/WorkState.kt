package com.aifactory.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 当前工作项目（进程级）。
 *
 * 起因：对话里让 Agent 改代码时，它不知道“当前在哪个项目”，于是反问
 * “是在 hello 里改，还是新建项目？”——这不是模型笨，是我们没把 UI 上选中的项目告诉它。
 * 项目页选中/新建时写这里，AgentEngine 每轮把项目路径与文件树注入系统提示。
 */
internal object WorkState {
    var currentProject by mutableStateOf<String?>(null)
}
