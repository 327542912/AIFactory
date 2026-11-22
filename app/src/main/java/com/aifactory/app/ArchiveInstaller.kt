package com.aifactory.app

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 安装 Runtime（Termux 风格 bootstrap zip）与 Android SDK，全部在应用内完成。 */
object ArchiveInstaller {

    private const val BUF = 1 shl 16

    // ---------- 公开接口 ----------

    suspend fun installBootstrap(ctx: Context, open: () -> InputStream, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) { installBootstrapStream(ctx, open(), progress) }

    suspend fun installBootstrapFromUrl(
        ctx: Context, url: String, sha256: String, progress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "bootstrap-download.zip")
        try {
            val actual = download(url, tmp, progress)
            if (sha256.isNotBlank() && !actual.equals(sha256.trim(), ignoreCase = true)) {
                throw IOException("SHA-256 不匹配：期望 ${sha256.trim()}，实际 $actual")
            }
            progress("下载完成，SHA-256：$actual")
            installBootstrapStream(ctx, FileInputStream(tmp), progress)
        } finally {
            tmp.delete()
        }
    }

    suspend fun installSdk(ctx: Context, open: () -> InputStream, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) { installSdkStream(ctx, open(), progress) }

    suspend fun installSdkFromUrl(ctx: Context, url: String, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "sdk-download.zip")
            try {
                val actual = download(url, tmp, progress)
                progress("下载完成，SHA-256：$actual")
                installSdkStream(ctx, FileInputStream(tmp), progress)
            } finally {
                tmp.delete()
            }
        }

    /** 把 zip 解压到 dest（阻塞，调用方放在 IO 线程）。 */
    fun unzipTo(zip: File, dest: File) {
        dest.mkdirs()
        FileInputStream(zip).use { unzip(it, dest) { } }
    }

    fun rmrf(f: File) {
        try {
            if (Files.isSymbolicLink(f.toPath())) { f.delete(); return }
        } catch (_: Exception) {
        }
        if (f.isDirectory) f.listFiles()?.forEach { rmrf(it) }
        f.delete()
    }

    // ---------- Runtime ----------

    private suspend fun installBootstrapStream(ctx: Context, input: InputStream, progress: (String) -> Unit) {
        if (!RuntimePaths.pathConsistent(ctx)) {
            throw IOException("无法通过 /data/data/${ctx.packageName}/files 访问数据目录（filesDir=${ctx.filesDir.path}）。")
        }
        val base = RuntimePaths.base(ctx)
        base.mkdirs()
        val prefix = RuntimePaths.prefix(ctx)
        val staging = File(base, "usr-staging")
        rmrf(staging)
        staging.mkdirs()

        progress("解压 Runtime…")
        val links = input.use { unzip(it, staging, progress) }
        progress("创建符号链接 ${links.size} 个…")
        applySymlinks(staging, links)

        if (!File(staging, "bin/bash").exists()) {
            rmrf(staging)
            throw IOException("这不是有效的 bootstrap 压缩包（缺少 bin/bash）。")
        }

        val old = File(base, "usr-old")
        rmrf(old)
        if (prefix.exists() && !prefix.renameTo(old)) throw IOException("无法替换旧 Runtime。")
        if (!staging.renameTo(prefix)) throw IOException("无法启用新 Runtime。")
        rmrf(old)

        RuntimePaths.home(ctx).mkdirs()
        File(prefix, "tmp").mkdirs()

        val second = File(prefix, "etc").walkTopDown().maxDepth(7)
            .firstOrNull { it.isFile && it.name == "termux-bootstrap-second-stage.sh" }
        if (second != null) {
            progress("运行 bootstrap 第二阶段…")
            val r = RuntimeEngine(ctx).run(
                "chmod +x ${shQuote(second.path)}; ${shQuote(second.path)}",
                RuntimePaths.home(ctx), 900
            )
            progress(
                if (r.ok) "第二阶段完成。"
                else "第二阶段退出码 ${r.exitCode}（可能不影响使用）：\n${tailText(r.output, 1200)}"
            )
        }
        progress("Runtime 安装完成。")
    }

    private fun applySymlinks(root: File, lines: List<String>) {
        for (line in lines) {
            val parts = line.split("←")
            if (parts.size != 2) continue
            val link = File(root, parts[1].trim())
            link.parentFile?.mkdirs()
            try {
                Os.symlink(parts[0].trim(), link.path)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EEXIST) throw IOException("符号链接失败 ${parts[1]}：${e.message}")
            }
        }
    }

    // ---------- SDK ----------

    private fun installSdkStream(ctx: Context, input: InputStream, progress: (String) -> Unit) {
        val home = RuntimePaths.home(ctx)
        home.mkdirs()
        val staging = File(home, "android-sdk-staging")
        rmrf(staging)
        staging.mkdirs()

        progress("解压 Android SDK…")
        input.use { unzip(it, staging, progress) }

        var root = staging
        val kids = staging.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (!File(staging, "platforms").exists() && !File(staging, "build-tools").exists() && kids.size == 1) {
            root = kids[0]
        }
        val sdk = RuntimePaths.sdk(ctx)
        rmrf(sdk)
        if (!root.renameTo(sdk)) throw IOException("无法启用 SDK 目录。")
        rmrf(staging)

        File(sdk, "licenses").mkdirs()
        File(sdk, "licenses/android-sdk-license").writeText(
            "24333f8a63b6825ea9c5514f83c2829b004d1fee\n" +
                "8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
                "d56f5187479451eabf01fb78af6dfcb131a6481e\n"
        )
        val ok = File(sdk, "platforms").isDirectory
        progress(if (ok) "Android SDK 安装完成。" else "已解压，但没有找到 platforms 目录，请确认压缩包内容。")
    }

    // ---------- 通用 ----------

    private fun download(url: String, dest: File, progress: (String) -> Unit): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")
            val total = conn.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = 0L
            conn.inputStream.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - last >= 2_000_000) {
                            last = done
                            progress("下载中 ${done / 1048576} / ${if (total > 0) (total / 1048576).toString() else "?"} MB")
                        }
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            conn.disconnect()
        }
    }

    /** 解压 zip，返回 SYMLINKS.txt 中的行（若有）。 */
    private fun unzip(input: InputStream, dest: File, progress: (String) -> Unit): List<String> {
        val symlinks = ArrayList<String>()
        val destCanon = dest.canonicalPath
        var count = 0
        ZipInputStream(BufferedInputStream(input, BUF)).use { zin ->
            val buf = ByteArray(BUF)
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name
                if (name == "SYMLINKS.txt") {
                    symlinks += zin.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
                    continue
                }
                val out = File(dest, name)
                val canon = out.canonicalPath
                if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) {
                    throw IOException("压缩包含非法路径：$name")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        val n = zin.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
                markExecutableIfNeeded(out)
                count++
                if (count % 500 == 0) progress("已解压 $count 个文件…")
            }
        }
        progress("共解压 $count 个文件。")
        return symlinks
    }

    /** zip 不保留权限位：ELF 与带 #! 的脚本设为 0700。 */
    private fun markExecutableIfNeeded(f: File) {
        if (f.length() < 2) return
        val head = ByteArray(4)
        val n = FileInputStream(f).use { it.read(head) }
        val elf = n == 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        val script = n >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
        if (elf || script) {
            try {
                Os.chmod(f.path, 448) // 0700
            } catch (_: Exception) {
                f.setExecutable(true, true)
            }
        }
    }
}
