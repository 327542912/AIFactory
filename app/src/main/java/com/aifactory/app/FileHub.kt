package com.aifactory.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class FileRecord(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long
)

private const val FILE_PREFS = "aifactory_file_hub"
private const val FILE_LIST = "imported_files"

fun loadFileRecords(context: Context): List<FileRecord> {
    return try {
        val raw = context.getSharedPreferences(FILE_PREFS, Context.MODE_PRIVATE)
            .getString(FILE_LIST, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            FileRecord(
                o.optString("uri"),
                o.optString("name", "未命名文件"),
                o.optString("mime", "application/octet-stream"),
                o.optLong("size", -1L)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveFileRecords(context: Context, records: List<FileRecord>) {
    val array = JSONArray()
    records.forEach {
        array.put(
            JSONObject()
                .put("uri", it.uri)
                .put("name", it.name)
                .put("mime", it.mime)
                .put("size", it.size)
        )
    }
    context.getSharedPreferences(FILE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(FILE_LIST, array.toString()).apply()
}

fun importFile(context: Context, uri: Uri): FileRecord? {
    return try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        var name = uri.lastPathSegment ?: "未命名文件"
        var size = -1L

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        val mime = context.contentResolver.getType(uri)
            ?: "application/octet-stream"

        FileRecord(uri.toString(), name, mime, size)
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

fun persistFileRecords(context: Context, records: List<FileRecord>) {
    saveFileRecords(context, records)
}

fun openImportedFile(context: Context, file: FileRecord): String? {
    return try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(file.uri), file.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
        null
    } catch (e: Exception) {
        e.message ?: "没有可打开此文件的应用"
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024L * 1024L) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
fun FileCenterPage(
    files: List<FileRecord>,
    onImport: () -> Unit,
    onOpen: (FileRecord) -> Unit,
    onRemove: (FileRecord) -> Unit,
    onCopyToProjects: (FileRecord) -> Unit = {}
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("文件中心", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text("${files.size} 个已导入文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onImport) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导入文件")
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("支持任意格式", fontWeight = FontWeight.SemiBold)
                Text(
                    "图片、音视频、PDF、Office、ZIP、APK、源码和本地模型均可选择。文件保留在原位置，AIFactory 保存访问授权和文件信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("还没有导入文件")
                Text("点击右上角「导入文件」开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.uri }) { file ->
                    Card {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    file.mime.startsWith("image/") -> Icons.Default.Image
                                    file.mime.startsWith("video/") -> Icons.Default.Movie
                                    file.mime.startsWith("audio/") -> Icons.Default.AudioFile
                                    file.name.endsWith(".apk", true) -> Icons.Default.Android
                                    file.name.endsWith(".zip", true) -> Icons.Default.FolderZip
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontWeight = FontWeight.Medium,
                                    maxLines = 2)
                                Text(
                                    "${formatFileSize(file.size)} · ${file.mime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            IconButton(onClick = { onCopyToProjects(file) }) {
                                Icon(Icons.Default.DriveFileMove, "复制到 ~/projects")
                            }
                            IconButton(onClick = { onRemove(file) }) {
                                Icon(Icons.Default.DeleteOutline, "移除记录")
                            }
                        }
                    }
                }
            }
        }
    }
}


/** 把 SAF 里选中的文件真正拷进 ~/projects（终端能直接用）。返回 null 表示成功。 */
fun copyFileToProjects(context: android.content.Context, record: FileRecord): String? {
    return try {
        val ws = RuntimePaths.workspace(context).apply { mkdirs() }
        val out = File(ws, record.name)
        val ins = context.contentResolver.openInputStream(android.net.Uri.parse(record.uri))
            ?: return "打不开源文件（授权可能已失效，重新导入一次）"
        ins.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        null
    } catch (e: Throwable) {
        e.message ?: "复制失败"
    }
}
