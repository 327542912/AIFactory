package com.aifactory.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 发布到 GitHub Releases。
 *
 * 为什么不是“一键上架应用商店”：Google Play 只收 AAB（本机没有 bundletool，也拿不到
 * Play 服务账号），且需要开发者账号与审核；GitHub Releases 是唯一能在设备上真闭环的发布通道：
 * 源码 zip + 签名 APK 一起上传，别人能下载安装。
 *
 * 全程走 GitHub REST API，不依赖 curl/git：curl 在 Runtime 里未必有，git 需要额外装。
 */
object Publisher {

    private const val API = "https://api.github.com"

    data class Result(val ok: Boolean, val summary: String, val releaseUrl: String?)

    fun publish(
        ctx: Context,
        cfg: AppConfig,
        projectDir: File,
        apk: File?,
        notes: String,
        onLog: (String) -> Unit
    ): Result {
        val repo = cfg.ghRepo.trim()
        val token = cfg.ghToken.trim()
        if (repo.isEmpty() || token.isEmpty()) {
            return Result(false, "没有配置 GitHub 仓库或 Token（设置 → GitHub 发布）。", null)
        }
        if (!repo.contains("/")) return Result(false, "仓库要写成 owner/repo，现在是：$repo", null)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val tag = (cfg.ghTagPrefix.ifBlank { "build" }) + "-" + stamp
        val name = projectDir.name + "-" + stamp

        // 1) 源码 zip（排除 build 产物，避免把几百 MB 中间文件传上去）
        onLog("打包源码 zip …")
        val zip = File(ctx.cacheDir, name + "-src.zip")
        runCatching { zipProject(projectDir, zip) }
            .onFailure { return Result(false, "源码打包失败：" + it.message, null) }
        onLog("源码包 " + zip.length() + " B")

        // 2) 建 release
        val body = """{"tag_name":"$tag","name":"$name","body":${json(notes.ifBlank { "自动发布：$name" })}, "draft":false,"prerelease":false}"""
        val created = request("POST", "$API/repos/$repo/releases", token, body.toByteArray(), "application/json", onLog)
        if (created == null) return Result(false, "创建 Release 失败（检查 token 是否有 repo 权限、仓库是否存在）。", null)
        val releaseId = created.optLong("id", 0)
        val htmlUrl = created.optString("html_url", "")
        onLog("Release 已创建：$htmlUrl")

        // 3) 上传源码 zip
        uploadAsset(repo, releaseId, token, zip, name + "-src.zip", onLog)
        // 4) 上传 APK（有就传）
        if (apk != null && apk.exists()) {
            uploadAsset(repo, releaseId, token, apk, apk.name, onLog)
        } else {
            onLog("没有构建产物，只发布了源码。")
        }
        zip.delete()
        return Result(true, "已发布到 $repo · tag $tag", htmlUrl)
    }

    /** 打源码 zip：跳过 build/ 与 .gradle/，其它文件全收。 */
    private fun zipProject(root: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            root.walkTopDown().forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (rel.contains("build/") || rel.startsWith(".gradle") || rel.contains("/.git/")) return@forEach
                if (!f.isFile) return@forEach
                if (f.length() > 32L * 1024 * 1024) return@forEach
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun uploadAsset(repo: String, releaseId: Long, token: String, file: File, assetName: String, onLog: (String) -> Unit) {
        val url = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=" +
            java.net.URLEncoder.encode(assetName, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            conn.outputStream.use { os -> file.inputStream().use { it.copyTo(os, 64 * 1024) } }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            onLog("上传 " + assetName + " → HTTP " + code + (if (code in 200..299) "（成功，${file.length()} B）" else "：" + text.take(300)))
        } catch (e: Exception) {
            onLog("上传 " + assetName + " 失败：" + e.message)
        } finally { conn.disconnect() }
    }

    private fun request(method: String, url: String, token: String, body: ByteArray?, type: String, onLog: (String) -> Unit): org.json.JSONObject? =
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; doOutput = body != null
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                if (body != null) setRequestProperty("Content-Type", type)
            }
            body?.let { b -> conn.outputStream.use { it.write(b) } }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) onLog("HTTP $code：" + text.take(400))
            conn.disconnect()
            if (code in 200..299 && text.isNotBlank()) org.json.JSONObject(text) else null
        } catch (e: Exception) {
            onLog("请求失败：" + e.message)
            null
        }

    private fun json(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
