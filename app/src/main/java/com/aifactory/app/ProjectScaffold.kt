package com.aifactory.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 新建项目的脚手架。
 *
 * 目标不是“造一个空文件夹”，而是把一个口头需求变成可执行的东西：
 * 需求 → PRD.md（可读、可改、可验收）→ 与方向匹配的最小可运行骨架。
 * 安卓方向的骨架必须能直接被本地链（aapt2→javac→D8→签名）编出 APK，
 * 所以要写清单、uses-sdk、入口 Activity，而不是给一个 gradle 工程让 gradle 去联网。
 */
object ProjectScaffold {

    enum class Kind(val label: String, val tag: String) {
        APP("Android App", "app"),
        WEB("网站 / 网页应用", "web"),
        WX_MINI("微信小程序", "wx"),
        ALI_MINI("支付宝小程序", "ali")
    }

    /** 把用户起的名字变成合法目录/包名片段：只留字母数字下划线，首字符必须是字母。 */
    fun slug(raw: String): String {
        val cleaned = raw.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (cleaned.isEmpty()) return "project"
        return if (cleaned.first().isLetter()) cleaned else "p" + cleaned
    }

    fun dirOf(workspace: File, rawName: String) = File(workspace, slug(rawName))

    /** 生成 PRD 文本（纯本地拼装，不依赖模型，保证“点了就有文档”）。 */
    fun prd(kind: Kind, name: String, brief: String): String {
        val briefClean = brief.trim().ifBlank { "（未填写，先按最小可用版本推进）" }
        val features = when (kind) {
            Kind.APP -> listOf(
                "启动页/主界面可打开，包名与图标信息完整",
                "核心页面能展示需求中描述的主要内容",
                "离线可用：不依赖网络也能启动",
                "构建产物通过自检（清单/classes.dex/resources.arsc/签名）"
            )
            Kind.WEB -> listOf(
                "首页能独立打开（单文件或少量静态资源）",
                "核心信息区展示需求描述的内容",
                "移动端可读：viewport、字号、触控区域合理",
                "无外部依赖即可预览（不引用被墙的 CDN）"
            )
            Kind.WX_MINI -> listOf(
                "app.json + pages/index 四件套齐全（wxml/wxss/js/json）",
                "首页能渲染需求描述的核心内容",
                "不依赖后端：先用静态数据跑通",
                "预留接口位：后端地址集中在一个常量里"
            )
            Kind.ALI_MINI -> listOf(
                "app.json + pages/index 四件套齐全（axml/acss/js/json）",
                "首页能渲染需求描述的核心内容",
                "不依赖后端：先用静态数据跑通",
                "预留接口位：后端地址集中在一个常量里"
            )
        }
        val tech = when (kind) {
            Kind.APP -> listOf(
                "minSdkVersion 26 / targetSdkVersion 28（targetSdk>28 会禁 execve 私有目录 ELF）",
                "构建走本地链：aapt2 → javac → D8 → zipalign → apksigner，全程离线",
                "不引入 gradle 依赖，避免设备端联网解析"
            )
            Kind.WEB -> listOf("纯静态 HTML/CSS/JS，双击即可打开", "不引入构建步骤")
            Kind.WX_MINI -> listOf("目录结构符合微信小程序规范", "开发者在微信开发者工具中导入即可预览")
            Kind.ALI_MINI -> listOf("目录结构符合支付宝小程序规范", "开发者在支付宝小程序开发者工具中导入即可预览")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return buildString {
            appendLine("# " + name.trim() + " · 需求文档（PRD）")
            appendLine()
            appendLine("- 生成时间：" + stamp)
            appendLine("- 项目方向：" + kind.label)
            appendLine("- 项目标识：" + slug(name))
            appendLine()
            appendLine("## 1. 需求原文")
            appendLine()
            appendLine(briefClean)
            appendLine()
            appendLine("## 2. 目标")
            appendLine()
            appendLine("把上面的需求做成一个 **能在手机上直接跑起来的最小可用版本**，先保证链路闭合（能构建、能打开、能看到核心内容），再谈扩展。")
            appendLine()
            appendLine("## 3. 目标用户与使用场景")
            appendLine()
            appendLine("- 用户：提出该需求的本人，单手操作手机")
            appendLine("- 场景：打开即用，不需要登录、不需要联网等待")
            appendLine()
            appendLine("## 4. 功能清单（首版）")
            appendLine()
            features.forEachIndexed { i, f -> appendLine((i + 1).toString() + ". " + f) }
            appendLine()
            appendLine("## 5. 验收标准")
            appendLine()
            appendLine("- [ ] 项目能在 App 内构建成功（日志无 ❌）")
            appendLine("- [ ] 产物可安装并打开（或网页可预览）")
            appendLine("- [ ] 首页/主界面展示了需求描述的核心内容")
            appendLine("- [ ] 构建日志已落盘到 .aifactory/logs，可复制/导出")
            appendLine()
            appendLine("## 6. 技术约束")
            appendLine()
            tech.forEach { appendLine("- " + it) }
            appendLine()
            appendLine("## 7. 后续可扩展（不在首版范围）")
            appendLine()
            appendLine("- 数据持久化、账号体系、联网接口")
            appendLine("- 多页面导航与设置页")
            appendLine("- 图标/启动图/商店素材")
        }
    }

    /** 创建目录与骨架，返回相对路径清单（用于日志）。 */
    fun create(workspace: File, rawName: String, kind: Kind, brief: String): List<String> {
        val root = dirOf(workspace, rawName).apply { mkdirs() }
        val id = slug(rawName)
        val created = mutableListOf<String>()
        fun put(rel: String, text: String) {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            f.writeText(text)
            created += rel
        }
        put("PRD.md", prd(kind, rawName, brief))
        val pkg = "com.aifactory." + id
        when (kind) {
            Kind.APP -> {
                put("app/src/main/AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28" />
    <application android:label="${escape(rawName)}" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""")
                put("app/src/main/java/com/aifactory/$id/MainActivity.java", """package $pkg;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 96, 48, 96);
        ll.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("${escape(rawName)}");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        ll.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(${javaLiteral(brief)});
        body.setTextSize(16);
        body.setTextColor(Color.DKGRAY);
        body.setPadding(0, 32, 0, 0);
        body.setGravity(Gravity.START);
        ll.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("AIFactory 本地链产物 · 需求见 PRD.md");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 48, 0, 0);
        ll.addView(hint);

        sv.addView(ll);
        setContentView(sv);
    }
}
""")
            }
            Kind.WEB -> {
                put("index.html", """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(rawName)}</title>
<style>
body{margin:0;font-family:-apple-system,"PingFang SC",sans-serif;background:#f6f4ef;color:#222}
main{max-width:720px;margin:0 auto;padding:32px 20px}
h1{font-size:26px;margin:0 0 16px}
p{line-height:1.7;color:#444;white-space:pre-wrap}
.tag{display:inline-block;background:#b4553c;color:#fff;border-radius:10px;padding:2px 10px;font-size:12px;margin-bottom:14px}
</style>
</head>
<body>
<main>
  <span class="tag">网页</span>
  <h1>${escape(rawName)}</h1>
  <p>${escape(brief)}</p>
</main>
</body>
</html>
""")
                put("style.css", "/* 预留：把样式从 index.html 抽出来时用 */\n")
            }
            Kind.WX_MINI -> {
                put("app.json", """{"pages":["pages/index/index"],"window":{"navigationBarTitleText":${jsonString(rawName)}}}""")
                put("pages/index/index.json", """{"navigationBarTitleText":${jsonString(rawName)}}""")
                put("pages/index/index.wxml", """<view class="page">
  <text class="title">${escape(rawName)}</text>
  <text class="body">${escape(brief)}</text>
  <text class="hint">AIFactory 生成 · 需求见 PRD.md</text>
</view>
""")
                put("pages/index/index.wxss", """.page{padding:32rpx}
.title{font-size:44rpx;font-weight:600;display:block;margin-bottom:24rpx}
.body{font-size:30rpx;color:#444;line-height:1.7;display:block;white-space:pre-wrap}
.hint{font-size:22rpx;color:#999;display:block;margin-top:40rpx}
""")
                put("pages/index/index.js", """Page({
  data: { title: ${jsonString(rawName)} },
  onLoad() {}
})
""")
            }
            Kind.ALI_MINI -> {
                put("app.json", """{"pages":["pages/index/index"],"window":{"defaultTitle":${jsonString(rawName)}}}""")
                put("pages/index/index.json", """{"defaultTitle":${jsonString(rawName)}}""")
                put("pages/index/index.axml", """<view class="page">
  <text class="title">${escape(rawName)}</text>
  <text class="body">${escape(brief)}</text>
  <text class="hint">AIFactory 生成 · 需求见 PRD.md</text>
</view>
""")
                put("pages/index/index.acss", """.page{padding:32rpx}
.title{font-size:44rpx;font-weight:600;display:block;margin-bottom:24rpx}
.body{font-size:30rpx;color:#444;line-height:1.7;display:block}
.hint{font-size:22rpx;color:#999;display:block;margin-top:40rpx}
""")
                put("pages/index/index.js", """Page({
  data: { title: ${jsonString(rawName)} },
  onLoad() {}
})
""")
            }
        }
        return created
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** 生成 Java 字符串字面量（保留换行，转义引号与反斜杠）。 */
    private fun javaLiteral(s: String): String {
        val body = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"" + body + "\""
    }
}
