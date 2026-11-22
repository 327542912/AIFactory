package com.aifactory.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 设备本地 flat apt 仓库。
 *
 * 痛点：这个 runtime 的包源托管在 GitHub Release，而用户网络连不上 github。
 * 于是 apt 连索引都拿不到 —— 插件页一律 "Unable to locate package"。
 * 解法：把用户选进来的 .deb 放进 files/localrepo，用 dpkg-deb 读出 control 生成
 * Packages（flat 仓库不需要压缩），再加一条 file:// 源。apt 从此能本地解析依赖，
 * 完全不走网络。
 */
object LocalRepo {

    fun dir(ctx: Context): File = File(RuntimePaths.base(ctx), "localrepo").apply { mkdirs() }

    fun uri(ctx: Context): String = "file://" + dir(ctx).path + "/"

    /** 把 APK 内置的库包（libandroid-shmem/spawn、libjpeg-turbo、littlecms）放到本地仓库。
     *  这四个 .so 是 openjdk-21 的 Depends，bootstrap 里没有；随包带走就不用再联网下 548 KB。 */
    fun stageBundled(ctx: Context): Int {
        val d = dir(ctx)
        val target = d.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        var copied = 0
        runCatching {
            val names = ctx.assets.list("local-debs") ?: emptyArray()
            for (n in names) {
                if (!n.endsWith(".deb") || n in target) continue
                val out = File(d, n)
                ctx.assets.open("local-debs/" + n).use { ins -> out.outputStream().use { ins.copyTo(it, 1 shl 16) } }
                copied++
            }
        }
        return copied
    }

    private fun field(rt: RuntimeEngine, dir: File, deb: File, name: String): String {
        // dpkg-deb -f 直接读 control，不需要我们自己解 ar/zstd
        val r = runBlockingShort(rt, "dpkg-deb -f " + shQuote(deb.name) + " " + name + " 2>/dev/null", dir)
        return r.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    private fun runBlockingShort(rt: RuntimeEngine, cmd: String, cwd: File): String =
        kotlinx.coroutines.runBlocking {
            runCatching { rt.run(cmd, cwd, 60).output }.getOrDefault("")
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    /** 官方 Termux 包（内部路径写死 com.termux）装进来会把本 runtime 的 bash/apt 换掉，一律拒收。 */
    fun isForeignPrefix(rt: RuntimeEngine, dir: File, deb: File): Boolean {
        val r = runBlockingShort(rt, "dpkg-deb -c " + shQuote(deb.name) + " 2>/dev/null | head -4", dir)
        return r.contains("data/data/com.termux/")
    }

    fun packageName(rt: RuntimeEngine, dir: File, deb: File): String = field(rt, dir, deb, "Package")

    /** 重新扫 localrepo，写 Packages / Release；返回 (可用索引?, 包名列表或错误)。 */
    fun rebuild(ctx: Context, rt: RuntimeEngine): Pair<Boolean, String> {
        val d = dir(ctx)
        val debs = d.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.sortedBy { it.name } ?: emptyList()
        if (debs.isEmpty()) return false to "本地仓库里没有 .deb"
        val sb = StringBuilder()
        val names = ArrayList<String>()
        for (f in debs) {
            val pkg = field(rt, d, f, "Package")
            if (pkg.isBlank()) continue
            names += pkg
            val ver = field(rt, d, f, "Version")
            val arch = field(rt, d, f, "Architecture").ifBlank { "aarch64" }
            val deps = field(rt, d, f, "Depends")
            val instSize = field(rt, d, f, "Installed-Size")
            val sect = field(rt, d, f, "Section").ifBlank { "utils" }
            val prio = field(rt, d, f, "Priority").ifBlank { "optional" }
            val maint = field(rt, d, f, "Maintainer").ifBlank { "AIFactory local" }
            val desc = field(rt, d, f, "Description").ifBlank { pkg }
            sb.append("Package: ").append(pkg).append('\n')
            sb.append("Version: ").append(ver).append('\n')
            sb.append("Architecture: ").append(arch).append('\n')
            if (deps.isNotBlank()) sb.append("Depends: ").append(deps).append('\n')
            if (instSize.isNotBlank()) sb.append("Installed-Size: ").append(instSize).append('\n')
            sb.append("Filename: ./").append(f.name).append('\n')
            sb.append("Size: ").append(f.length()).append('\n')
            sb.append("SHA256: ").append(sha256(f)).append('\n')
            sb.append("Section: ").append(sect).append('\n')
            sb.append("Priority: ").append(prio).append('\n')
            sb.append("Maintainer: ").append(maint).append('\n')
            sb.append("Description: ").append(desc.replace('\n', ' ')).append("\n\n")
        }
        if (names.isEmpty()) return false to "无法读取 deb 的 control 字段（dpkg-deb 不可用？）"
        File(d, "Packages").writeText(sb.toString())
        val release = buildString {
            append("Origin: AIFactory\n")
            append("Label: AIFactory Local\n")
            append("Suite: stable\n")
            append("Codename: aifactory\n")
            append("Architectures: aarch64 all\n")
            append("Components: main\n")
            append("Description: 设备本地离线包\n")
            // apt 要 RFC2822 的 Date，缺了或格式不对就报 Invalid 'Date' entry
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            append("Date: " + fmt.format(Date()) + "\n")
        }
        File(d, "Release").writeText(release)
        return true to names.joinToString(" ")
    }

    /** 幂等：把 file:// 本地源写进 sources.list（没有就追加）。 */
    fun ensureSource(ctx: Context): Boolean {
        val sl = File(RuntimePaths.prefix(ctx), "etc/apt/sources.list")
        if (!sl.exists()) sl.parentFile?.mkdirs()
        val cur = runCatching { sl.readText() }.getOrDefault("")
        if (cur.contains("file://" + dir(ctx).path)) return false
        val line = "deb [trusted=yes] " + uri(ctx) + " ./\n"
        sl.appendText((if (cur.isNotEmpty() && !cur.endsWith("\n")) "\n" else "") + "# 设备本地离线源\n" + line)
        return true
    }
}
