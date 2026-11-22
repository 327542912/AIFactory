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

    /** 发布签名密钥口令：首次使用时随机生成，只保存在应用私有存储。 */
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
