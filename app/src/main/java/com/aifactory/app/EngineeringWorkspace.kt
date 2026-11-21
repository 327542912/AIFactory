package com.aifactory.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ProjectInfo(
    val id: String,
    val name: String,
    val path: String,
    val kind: String,
    val fileCount: Int,
    val updatedAt: Long
)

data class ProjectScan(
    val project: ProjectInfo,
    val files: List<String>,
    val buildSystem: String,
    val buildCommand: String?,
    val testCommand: String?,
    val warnings: List<String>
)

/**
 * App-private project workspace and guarded file operations.
 * Projects are kept below filesDir/aifactory/workspace/projects.
 * No path supplied by a model is trusted without canonical-path validation.
 */
class EngineeringWorkspace(private val context: Context) {
    private val base = File(context.filesDir, "aifactory/workspace")
    private val projectsDir = File(base, "projects")
    private val checkpointDir = File(base, "checkpoints")
    private val prefs = context.getSharedPreferences("aifactory_projects", Context.MODE_PRIVATE)

    init {
        check(projectsDir.mkdirs() || projectsDir.isDirectory) { "无法创建项目目录" }
        check(checkpointDir.mkdirs() || checkpointDir.isDirectory) { "无法创建检查点目录" }
    }

    @Synchronized
    fun listProjects(): List<ProjectInfo> {
        val dirs = projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { dir ->
            val meta = readMeta(dir) ?: return@mapNotNull null
            ProjectInfo(
                meta.optString("id", dir.name),
                meta.optString("name", dir.name),
                dir.canonicalPath,
                detectKind(dir),
                countSourceFiles(dir),
                dir.lastModified()
            )
        }.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun createProject(rawName: String): ProjectInfo {
        val name = rawName.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "-").trim('-', '.', '_')
        require(name.isNotBlank()) { "项目名称不能为空" }
        require(name.length <= 64) { "项目名称不能超过 64 个字符" }
        val id = UUID.randomUUID().toString()
        val dir = File(projectsDir, "${name.take(40)}-${id.take(8)}")
        check(dir.mkdirs()) { "创建项目目录失败" }
        val meta = JSONObject()
            .put("id", id).put("name", name).put("created_at", System.currentTimeMillis())
        File(dir, ".aifactory-project.json").writeText(meta.toString(2), Charsets.UTF_8)
        File(dir, "README.md").writeText("# $name\n\n由 AIFactory 创建的工程项目。\n", Charsets.UTF_8)
        return listProjects().first { it.id == id }
    }

    fun scan(projectId: String): ProjectScan {
        val dir = projectDir(projectId)
        val files = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val ignored = setOf(".git", ".gradle", "build", ".idea", ".cxx", "node_modules", "__pycache__")
        var truncated = false
        fun visit(current: File, relative: String) {
            if (files.size >= 1500) { truncated = true; return }
            val children = current.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (child.name in ignored || child.name.startsWith(".")) continue
                val rel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (child.isDirectory) visit(child, rel)
                else if (child.isFile) files += rel
                if (files.size >= 1500) { truncated = true; return }
            }
        }
        visit(dir, "")
        if (truncated) warnings += "文件清单达到 1500 项上限，扫描结果可能不完整。"
        val build = when {
            File(dir, "gradlew").isFile || File(dir, "build.gradle").isFile || File(dir, "build.gradle.kts").isFile -> "Gradle"
            File(dir, "pom.xml").isFile -> "Maven"
            File(dir, "CMakeLists.txt").isFile -> "CMake"
            File(dir, "pyproject.toml").isFile || File(dir, "requirements.txt").isFile -> "Python"
            File(dir, "package.json").isFile -> "Node.js"
            else -> "未知"
        }
        val buildCmd = when (build) {
            "Gradle" -> if (File(dir, "gradlew").isFile) "./gradlew assembleDebug" else null
            "Maven" -> "mvn package"
            "CMake" -> "cmake -S . -B build && cmake --build build"
            "Python" -> null
            "Node.js" -> "npm run build"
            else -> null
        }
        val testCmd = when (build) {
            "Gradle" -> if (File(dir, "gradlew").isFile) "./gradlew test" else null
            "Maven" -> "mvn test"
            "Python" -> "python -m pytest"
            "Node.js" -> "npm test"
            else -> null
        }
        if (buildCmd == null && build != "Python") warnings += "未识别到可直接执行的构建入口。"
        if (build == "Gradle" && !File(dir, "gradlew").canExecute()) warnings += "gradlew 不可执行；执行前需确认运行时具备 Java/Gradle。"
        if (build == "未知") warnings += "未识别工程类型，请检查项目根目录和构建配置。"
        val p = listProjects().first { it.id == projectId }
        return ProjectScan(p, files, build, buildCmd, testCmd, warnings)
    }

    fun readFile(projectId: String, relativePath: String, maxBytes: Int = 512 * 1024): String {
        val file = resolve(projectDir(projectId), relativePath)
        require(file.isFile) { "文件不存在" }
        require(file.length() <= maxBytes) { "文件超过读取上限（${maxBytes / 1024} KB）" }
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun writeFile(projectId: String, relativePath: String, content: String): String {
        require(content.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "单文件写入上限为 2 MB" }
        val root = projectDir(projectId)
        val target = resolve(root, relativePath)
        require(target.name != ".aifactory-project.json") { "不能修改项目内部元数据" }
        val checkpoint = checkpoint(projectId, relativePath, target)
        check(target.parentFile?.mkdirs() != false) { "创建父目录失败" }
        val temp = File(target.parentFile, target.name + ".aifactory-tmp")
        temp.writeText(content, Charsets.UTF_8)
        check(temp.renameTo(target)) { "原子替换文件失败" }
        return checkpoint
    }

    @Synchronized
    fun checkpoint(projectId: String, relativePath: String, target: File): String {
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val dir = File(checkpointDir, projectId).apply { mkdirs() }
        val record = JSONObject()
            .put("id", id).put("path", relativePath)
            .put("existed", target.isFile).put("created_at", System.currentTimeMillis())
        if (target.isFile) {
            val backup = File(dir, "$id.before")
            target.copyTo(backup, overwrite = false)
            record.put("backup", backup.name)
        }
        File(dir, "$id.json").writeText(record.toString(), Charsets.UTF_8)
        return id
    }

    @Synchronized
    fun rollback(projectId: String, checkpointId: String) {
        require(checkpointId.matches(Regex("[0-9]+-[a-f0-9]{8}"))) { "检查点 ID 无效" }
        val dir = File(checkpointDir, projectId).canonicalFile
        val recordFile = File(dir, "$checkpointId.json").canonicalFile
        require(recordFile.parentFile == dir && recordFile.isFile) { "检查点不存在" }
        val record = JSONObject(recordFile.readText(Charsets.UTF_8))
        val target = resolve(projectDir(projectId), record.getString("path"))
        if (record.optBoolean("existed")) {
            val backup = File(dir, record.getString("backup")).canonicalFile
            require(backup.parentFile == dir && backup.isFile) { "备份文件缺失" }
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
        } else {
            if (target.isFile) check(target.delete()) { "删除新建文件失败" }
        }
    }

    fun projectPath(projectId: String): File = projectDir(projectId)

    private fun projectDir(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "项目 ID 无效" }
        val match = listProjects().firstOrNull { it.id == id } ?: error("项目不存在")
        return File(match.path).canonicalFile
    }

    private fun resolve(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith("/") && !relative.contains('\\u0000')) { "文件路径无效" }
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "拒绝访问项目目录之外的路径" }
        return target
    }

    private fun readMeta(dir: File): JSONObject? = try {
        JSONObject(File(dir, ".aifactory-project.json").readText(Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun detectKind(dir: File): String = when {
        File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists() -> "Android/Gradle"
        File(dir, "CMakeLists.txt").exists() -> "C/C++"
        File(dir, "pyproject.toml").exists() -> "Python"
        File(dir, "package.json").exists() -> "JavaScript"
        else -> "通用工程"
    }

    private fun countSourceFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().onEnter { it.name !in setOf(".git", ".gradle", "build", "node_modules") }
            .filter { it.isFile && it.name != ".aifactory-project.json" }
            .take(1501).forEach { count++ }
        return count
    }
}
