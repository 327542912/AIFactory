package com.aifactory.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * git 推送（用设置页里已经配置的 Token，不向用户再要一次）。
 *
 * 关键：Agent 在 Runtime 里跑 `git push`，那个进程读不到 App 的设置，也没有凭据助手，
 * 所以以前只能回头问用户要 Token。这里把 Token 以 HTTP 头的形式注入 git（不进 URL、不进日志），
 * 输出一律做脱敏。
 */
object GitOps {

    data class Out(val ok: Boolean, val text: String)

    suspend fun push(
        ctx: Context, rt: RuntimeEngine, cfg: AppConfig, dir: File,
        repoArg: String, branchArg: String, onLog: (String) -> Unit
    ): Out {
        val token = cfg.ghToken.trim()
        if (token.isEmpty()) return Out(false, "设置里没有 GitHub Token（设置 → 发布：GitHub → Token）。填一次就能直接推，不用每次问我。")
        if (!dir.isDirectory) return Out(false, "项目目录不存在：" + dir.path)

        // 单仓库布局：从项目目录向上找最近的 .git，推到同一个仓库（项目就是它的子目录）
        val root = gitRootOf(dir)

        val repo = when {
            repoArg.contains("/") -> repoArg.trim()
            cfg.ghRepo.contains("/") -> cfg.ghRepo.trim()
            else -> deriveRepo(dir)
        }
        if (repo.isBlank()) return Out(false, "不知道该推到哪个仓库：请在设置页填 仓库 owner/repo，或在参数里给 repo。")

        // 1) 仓库不存在就建（用同一枚 Token）
        val exists = api("GET", "https://api.github.com/repos/" + repo, token)
        if (exists.first == 401 || exists.first == 403) {
            return Out(false, "Token 失效或权限不足（HTTP " + exists.first + "）：去 GitHub 重新生成一枚勾了 repo 的 token，粘进设置里。")
        }
        if (exists.first == 404) {
            val name = repo.substringAfter("/")
            onLog("仓库 " + repo + " 不存在，用 Token 新建 public 库…")
            val made = api("POST", "https://api.github.com/user/repos", token,
                "{\"name\":\"" + name + "\",\"private\":false,\"auto_init\":false}")
            if (made.first !in 200..299) return Out(false, "建仓失败（HTTP " + made.first + "）：" + made.second.take(300))
            cfg.ghRepo = repo
        }

        // 2) 本地仓库保证有提交
        val git = "git -C " + q(root.path) + " "
        val hasGit = rt.run(git + "rev-parse --git-dir", root, 20).exitCode == 0
        if (!hasGit) {
            rt.run(git + "init -q", root, 20)
        }
        // 身份没配就自动配（只配本仓库），否则 commit 直接失败
        rt.run(git + "config user.name AIFactory || true", root, 20)
        rt.run(git + "config user.email aifactory@local || true", root, 20)
        // 提交前密钥扫描：命中签名/安装包/令牌特征就拒绝
        val scan = secretScan(rt, root, git)
        if (scan.isNotBlank()) return Out(false, "提交被拦下：工作区里有不该进版本库的东西。\n" + scan + "\n把它们加进 .gitignore，或移出仓库根后再推。")
        rt.run(git + "add -A", root, 60)
        if (rt.run(git + "rev-parse HEAD", root, 20).exitCode != 0) {
            rt.run(git + "commit -q -m \"init\" || true", root, 60)
        }
        if (rt.run(git + "status --porcelain", root, 20).output.isNotBlank()) {
            rt.run(git + "commit -q -m \"chore: sync from AIFactory\" || true", root, 60)
        }
        val headOk = rt.run(git + "rev-parse HEAD", root, 20).exitCode == 0
        if (!headOk) return Out(false, "本地没有可推送的提交（没有文件？或被 .gitignore 全排除了）。")

        // 3) remote 只放干净 URL；Token 走一次性 HTTP 头，不出现在 URL / 远程配置 / 日志
        rt.run(git + "remote remove origin", root, 20)
        rt.run(git + "remote add origin https://github.com/" + repo + ".git", root, 20)
        val branch = branchArg.ifBlank { "main" }
        rt.run(git + "branch -M " + branch, root, 20)

        val basic = Base64.encodeToString(("x-access-token:" + token).toByteArray(), Base64.NO_WRAP)
        val cmd = "env GIT_TERMINAL_PROMPT=0 " + git +
            "-c http.https://github.com/.extraheader=\"Authorization: basic " + basic + "\" " +
            "push -u origin HEAD:" + branch
        val r = rt.run(cmd, root, 180)
        val out = redact(r.output, token, basic)
        onLog("git push 退出码 " + r.exitCode)
        return if (r.exitCode == 0) {
            Out(true, "已推送到 https://github.com/" + repo + "（分支 " + branch + "）\n" + out.takeLast(1200))
        } else {
            val hint = when {
                out.contains("Invalid username or token", true) || out.contains("Authentication failed", true) ->
                    "Token 被 GitHub 拒绝：重新生成一枚（勾 repo），粘进 设置 → 发布：GitHub。"
                out.contains("rejected", true) && out.contains("fetch first", true) ->
                    "远端已有提交，和本地分叉了：用 git_push 时给 force=true，或先 git pull 再推。"
                out.contains("does not appear to be a git repository", true) ->
                    "远端地址不对：确认 仓库 owner/repo 拼对了，且 Token 能看到它。"
                else -> "看下面的 git 输出定位。"
            }
            Out(false, "推送失败。\n" + hint + "\n--- git 输出（已脱敏）---\n" + out.takeLast(1500))
        }
    }

    /** 从项目目录向上最多四层找 .git，找不到就用项目目录自己。 */
    private fun gitRootOf(dir: File): File {
        var f: File? = dir.canonicalFile
        repeat(4) {
            val c = f ?: return dir
            if (c.isDirectory && File(c, ".git").let { it.isDirectory || it.isFile }) return c
            f = c.parentFile
        }
        return dir
    }

    /** 密钥/产物扫描：只看会被提交的路径（已跟踪 + 未忽略），命中即返回文件名列表。 */
    private suspend fun secretScan(rt: RuntimeEngine, root: File, git: String): String {
        val listed = rt.run(git + "ls-files --cached --others --exclude-standard", root, 40).output
        val badDirs = listOf("/app/build/", "/build/", "/.gradle/", "/.attachments/", "/.dl/", "/.inbox/", "/.aifactory/")
        val badExt = listOf(".apk", ".aab", ".jks", ".keystore", ".aab.zip")
        val hits = listed.lineSequence().filter { it.isNotBlank() }.filter { path ->
            badExt.any { path.endsWith(it, true) } || badDirs.any { path.contains(it) } || path.endsWith("local.properties")
        }.toList()
        if (hits.isEmpty()) return ""
        return hits.take(12).joinToString("\n") { " - " + it } + (if (hits.size > 12) "\n - …共 " + hits.size + " 个" else "")
    }

    private fun deriveRepo(dir: File): String = runCatching {
        val cfgText = File(dir, ".git/config")
        if (!cfgText.exists()) return ""
        val m = Regex("github\\.com[/:]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)").find(cfgText.readText())
        m?.groupValues?.get(1)?.removeSuffix(".git") ?: ""
    }.getOrDefault("")

    private fun api(method: String, url: String, token: String, body: String? = null): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Authorization", "Bearer " + token)
                setRequestProperty("Accept", "application/vnd.github+json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        } finally {
            c?.disconnect()
        }
    }

    private fun redact(s: String, token: String, basic: String): String =
        s.replace(token, "***").replace(basic, "***").replace(Regex("https://[^\\s]*@github\\.com"), "https://github.com")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
