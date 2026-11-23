package com.aifactory.app

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Runtime 目录布局与 Termux 相同（prefix = files/usr，home = files/home）。
 * Termux 系二进制把 /data/data/<包名>/files/usr 写死在文件里，所以这里必须用硬编码路径，
 * 而不是 context.filesDir（在多用户下 filesDir 可能是 /data/user/N/...）。
 */
object RuntimePaths {
    fun base(ctx: Context) = File("/data/data/${ctx.packageName}/files")
    fun prefix(ctx: Context) = File(base(ctx), "usr")
    fun home(ctx: Context) = File(base(ctx), "home")
    fun workspace(ctx: Context) = File(home(ctx), "projects")
    fun sdk(ctx: Context) = File(home(ctx), "android-sdk")
    fun outputs(ctx: Context) = File(base(ctx), "outputs")
    fun logs(ctx: Context) = File(home(ctx), ".aifactory/logs")

    /** 用探针文件确认 /data/data/<包名>/files 与真实 filesDir 是同一个目录。 */
    fun pathConsistent(ctx: Context): Boolean = try {
        val probe = File(ctx.filesDir, ".path_probe")
        probe.writeText("x")
        val ok = File(base(ctx), ".path_probe").exists()
        probe.delete()
        ok
    } catch (_: Exception) {
        false
    }
}

class AppConfig(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("aifactory_config", Context.MODE_PRIVATE)

    private fun get(key: String, def: String): String = p.getString(key, def) ?: def
    private fun put(key: String, value: String) { p.edit().putString(key, value).apply() }

    var baseUrl: String
        get() = get("base_url", "https://api.openai.com/v1")
        set(v) = put("base_url", v)

    var model: String
        get() = get("model", "gpt-4o-mini")
        set(v) = put("model", v)

    /** 每条用户消息内 Agent 最多调用工具的次数。 */
    var maxSteps: Int
        get() = p.getInt("max_steps", 200)
        set(v) { p.edit().putInt("max_steps", v.coerceIn(1, 100000)).apply() }

    var apiKey: String
        get() = get("api_key", "")
        set(v) = put("api_key", v)

    /** 备用通道：主通道连续 429 时自动切换，避免长任务半路掉线（可为本地 llama.cpp server）。 */
    var backupBaseUrl: String
        get() = get("backup_base_url", "")
        set(v) = put("backup_base_url", v)

    var backupModel: String
        get() = get("backup_model", "")
        set(v) = put("backup_model", v)

    var backupApiKey: String
        get() = get("backup_api_key", "")
        set(v) = put("backup_api_key", v)

    /** 每次任务开始先探测主/备通道延迟与可用性，别让用户拿 3 次失败去换一次切换。 */
    var autoProbe: Boolean
        get() = p.getBoolean("auto_probe", true)
        set(v) = p.edit().putBoolean("auto_probe", v).apply()

    /** 额外通道池：一行一个，格式 `名称|baseUrl|模型|Key`（Key 省略则用主通道 Key）。 */
    var extraChannels: String
        get() = get("extra_channels", "")
        set(v) = put("extra_channels", v)

    val hasBackup: Boolean
        get() = backupBaseUrl.isNotBlank() && backupModel.isNotBlank()

    var bootstrapUrl: String
        get() = get("bootstrap_url", "https://github.com/327542912/AIFactory/releases/download/runtime-latest/bootstrap-aarch64.zip")
        set(v) = put("bootstrap_url", v)

    var bootstrapSha: String
        get() = get("bootstrap_sha", "")
        set(v) = put("bootstrap_sha", v)

    /** apt 软件源（flat 仓库，托管在 GitHub Release）。 */
    var repoUrl: String
        get() = get("repo_url", "https://github.com/327542912/AIFactory/releases/download/runtime-repo/")
        set(v) = put("repo_url", v)

    /** Android SDK（aarch64 静态 aapt2 + platforms/android-34 + build-tools/34.0.0）。 */
    var sdkUrl: String
        get() = get("sdk_url", "https://github.com/327542912/AIFactory/releases/download/sdk-latest/android-sdk-aarch64.zip")
        set(v) = put("sdk_url", v)

    /** 主流水线之外的补充 apt 源（Gradle 8.7 等）；重建 runtime-repo 不会覆盖。 */
    var extraRepoUrl: String
        get() = get("extra_repo_url", "https://github.com/327542912/AIFactory/releases/download/runtime-extra/")
        set(v) = put("extra_repo_url", v)

    /** 下载加速前缀（可选）。例如 https://ghproxy.net → 实际请求 https://ghproxy.net/https://github.com/... */
    var mirrorPrefix: String
        get() = get("mirror_prefix", "")
        set(v) = put("mirror_prefix", v)

    /** 发布签名密钥口令：首次使用时随机生成，只保存在应用私有存储。 */
    /** GitHub 发布：仓库写 owner/repo，token 需要 repo 权限。 */
    var ghRepo: String
        get() = get("gh_repo", "")
        set(v) = put("gh_repo", v.trim())

    var ghToken: String
        get() = get("gh_token", "")
        set(v) = put("gh_token", v.trim())

    /** 版本号由项目名 + 时间戳生成，默认沿用。 */
    var ghTagPrefix: String
        get() = get("gh_tag", "build")
        set(v) = put("gh_tag", v.trim())

    val keystorePassword: String
        get() {
            var v = p.getString("ks_pass", null)
            if (v == null) {
                val b = ByteArray(18)
                SecureRandom().nextBytes(b)
                v = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                p.edit().putString("ks_pass", v).apply()
            }
            return v
        }
}

fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun tailText(s: String, max: Int): String =
    if (s.length <= max) s else "…(前面已省略)\n" + s.takeLast(max)
