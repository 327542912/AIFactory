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
        ctx: Context, url: String, sha256: String, mirror: String = "", progress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "bootstrap-download.zip")
        try {
            val actual = download(url, tmp, progress, mirror)
            if (sha256.isNotBlank() && !actual.equals(sha256.trim(), ignoreCase = true)) {
                tmp.delete()   // 哈希不对说明这份残包没救，删掉重来
                throw IOException("SHA-256 不匹配：期望 ${sha256.trim()}，实际 $actual")
            }
            progress("下载完成，SHA-256：$actual")
            installBootstrapStream(ctx, FileInputStream(tmp), progress)
            tmp.delete()
        } catch (e: Exception) {
            // 保留残包，下次点「下载并安装」会从断点续传
            progress("已保留 ${tmp.length() / 1048576} MB 进度，下次点击可续传")
            throw e
        }
    }

    suspend fun installSdk(ctx: Context, open: () -> InputStream, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) { installSdkStream(ctx, open(), progress) }

    suspend fun installSdkFromUrl(ctx: Context, url: String, mirror: String = "", progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "sdk-download.zip")
            try {
                val actual = download(url, tmp, progress, mirror)
                progress("下载完成，SHA-256：$actual")
                installSdkStream(ctx, FileInputStream(tmp), progress)
                tmp.delete()
            } catch (e: Exception) {
                progress("已保留 ${tmp.length() / 1048576} MB 进度，下次点击可续传")
                throw e
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

    /**
     * 下载到 dest。
     *
     * GitHub Release 的直链会 302 跳到 objects.githubusercontent.com；挂 VPN / 移动网络时，
     * 中途中被掐断一次就报 "Software caused connection abort"。所以这里：
     *   1. 手动跟 3xx（最多 5 跳），不依赖 HttpURLConnection 的自动跟随；
     *   2. 带 User-Agent / Accept，部分 CDN 缺 UA 会直接断；
     *   3. 失败按 HTTP Range 断点续传，最多 5 次，成功后再算整份 sha256。
     */
    /** 拼接加速前缀：镜像只包一层 URL，不改文件内容，sha256 仍按原文件校验。 */
    private fun applyMirror(url: String, mirror: String): String {
        val m = mirror.trim()
        if (m.isEmpty() || !url.startsWith("http")) return url
        return m.trimEnd('/') + "/" + url
    }

    private fun download(url: String, dest: File, progress: (String) -> Unit, mirror: String = ""): String {
        dest.parentFile?.mkdirs()
        var attempt = 0
        var last: Exception? = null
        while (attempt < 5) {
            attempt++
            var conn: HttpURLConnection? = null
            try {
                val have = if (dest.exists()) dest.length() else 0L
                conn = connect(applyMirror(url, mirror), have)
                val code = conn.responseCode
                if (code == 416) {
                    dest.delete()
                    throw IOException("服务器拒绝了续传位置（HTTP 416），已重置为从头下载")
                }
                if (code !in 200..299) throw IOException("下载失败：HTTP $code")
                val append = code == 206 && have > 0
                val remain = conn.contentLengthLong
                val total = if (append) have + remain else remain
                progress(if (append) "续传：已有 ${have / 1048576} MB（第 $attempt 次尝试）" else "开始下载（第 $attempt 次尝试）")
                var done = if (append) have else 0L
                var lastTick = done
                FileOutputStream(dest, append).use { out ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastTick >= 2_000_000) {
                                lastTick = done
                                progress("下载中 ${done / 1048576} / ${if (total > 0) (total / 1048576).toString() else "?"} MB")
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("连接提前结束（$done / $total 字节），已保留进度")
                return sha256(dest)
            } catch (e: Exception) {
                last = e
                progress("第 $attempt 次失败：${e.message ?: e.javaClass.simpleName}")
                if (e is java.net.MalformedURLException || attempt >= 5) break
                try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
            } finally {
                conn?.disconnect()
            }
        }
        throw IOException(
            "下载失败（已重试 5 次）：${last?.message ?: "未知错误"}。" +
                "可改用浏览器打开同一地址下载 zip，再点「选择本地 zip」导入。", last
        )
    }

    /** 建连接并手动跟重定向；resumeFrom > 0 时带 Range 头。 */
    private fun connect(url: String, resumeFrom: Long): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "AIFactory/${BuildConfig.VERSION_NAME} (Android)")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Connection", "close")
            if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = c.responseCode
            if (code in 301..308) {
                val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code 但没有 Location 头")
                c.disconnect()
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                hops++
                if (hops > 5) throw IOException("重定向超过 5 跳：$loc")
                continue
            }
            return c
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
