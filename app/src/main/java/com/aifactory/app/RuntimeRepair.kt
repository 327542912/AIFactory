package com.aifactory.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自愈：Runtime 的 usr 树被上一次「替换到一半被打断」的安装弄丢后，
 * files/usr-old 仍然留着完整的一套。启动时把可用的那套恢复成 files/usr，
 * 用户不用重下 29 MB bootstrap。
 */
object RuntimeRepair {

    @Volatile var lastAction: String? = null
        private set

    fun ensureUsr(ctx: Context) {
        val base = RuntimePaths.base(ctx)
        val prefix = RuntimePaths.prefix(ctx)
        val old = File(base, "usr-old")
        if (!old.isDirectory) return
        val oldHasBash = File(old, "bin/bash").exists()

        if (!prefix.exists()) {
            if (oldHasBash) {
                if (old.renameTo(prefix)) lastAction = "已从 usr-old 恢复 Runtime"
                return
            }
        }
        if (!File(prefix, "bin/bash").exists() && oldHasBash) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val broken = File(base, "usr-broken-$stamp")
            if (prefix.renameTo(broken)) {
                if (old.renameTo(prefix)) {
                    lastAction = "Runtime 已从备份恢复（原损坏目录保留为 usr-broken-$stamp）"
                } else {
                    broken.renameTo(prefix)
                }
            }
            return
        }
        // 正常情况：旧目录只是残留，清掉省空间
        if (File(prefix, "bin/bash").exists()) File(base, "usr-old").deleteRecursively()
    }
}
