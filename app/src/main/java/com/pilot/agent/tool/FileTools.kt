package com.pilot.agent.tool

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.pilot.agent.Dbg
import com.pilot.agent.perception.ScreenReader
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sandboxed file operations for the explicit file paths the user names.
 *
 * The original text is never written to logs or the audit trail. Every path is
 * canonicalized before the root check, so ".." and symlink escapes are rejected.
 */
class FileTools(
    private val context: Context,
    private val readerProvider: () -> ScreenReader?,
) {
    private val externalRoot: File? by lazy {
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
    }

    private val allowedRoots: List<File> by lazy {
        buildList {
            externalRoot?.let(::add)
            add(context.filesDir.canonicalFile)
            add(context.cacheDir.canonicalFile)
            context.getExternalFilesDir(null)?.let { add(it.canonicalFile) }
            context.getExternalCacheDir()?.let { add(it.canonicalFile) }
        }.distinctBy { it.absolutePath }
    }

    fun listFiles(rawPath: String): ToolResult {
        val target = resolve(rawPath, mustExist = true)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内，或路径包含越界跳转。")
        if (!target.isDirectory) return ToolResult.error("这不是文件夹：${target.name}")
        val children = try {
            target.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            ).orEmpty()
        } catch (error: Throwable) {
            return errorResult("读取文件夹失败", error)
        }
        if (children.isEmpty()) return ToolResult.success("${target.absolutePath} 里是空的。")
        val list = children.take(60).joinToString("\n") { file ->
            val type = if (file.isDirectory) "文件夹" else humanSize(file.length())
            "• ${file.name}（$type）"
        }
        val suffix = if (children.size > 60) "\n另有 ${children.size - 60} 项未显示。" else ""
        return ToolResult.success(
            message = "${target.absolutePath}：\n$list$suffix",
            spoken = "找到 ${children.size} 项，路径是 ${target.absolutePath}。",
        )
    }

    fun readFile(rawPath: String): ToolResult {
        val target = resolve(rawPath, mustExist = true)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内，或路径包含越界跳转。")
        if (!target.isFile) return ToolResult.error("没有找到这个文件。")
        if (target.length() > MAX_READ_BYTES) {
            return ToolResult.error(
                "文件有 ${humanSize(target.length())}，超过单次读取上限 ${humanSize(MAX_READ_BYTES)}。"
            )
        }
        val bytes = runCatching { target.readBytes() }
            .getOrElse { return errorResult("读取文件失败", it) }
        if (bytes.any { it == 0.toByte() }) {
            return ToolResult.error("这是二进制文件，不能按文本读取。")
        }
        val text = runCatching { bytes.toString(Charsets.UTF_8) }
            .getOrElse { return errorResult("文件不是 UTF-8 文本", it) }
        val trimmed = text.take(MAX_OUTPUT_CHARS)
        val suffix = if (text.length > trimmed.length) "\n……内容已截断。" else ""
        return ToolResult.success(
            message = "${target.name}：\n$trimmed$suffix",
            spoken = "已读取 ${target.name}，${text.length} 个字符。",
        )
    }

    fun writeFile(rawPath: String, content: String, overwrite: Boolean = false): ToolResult {
        val target = resolve(rawPath, mustExist = false)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内，或路径包含越界跳转。")
        if (!hasAllFilesAccess() && !isInsideAppStorage(target)) {
            return ToolResult.unavailable(
                "共享存储写入需要“所有文件访问”权限。请到 Pilot 设置里的“本机工具与长期记忆”开启。"
            )
        }
        if (target.isDirectory) return ToolResult.error("目标是文件夹，不能写入文本。")
        if (target.exists() && !overwrite) {
            return ToolResult.error("同名文件已经存在。若要替换，请在指令里明确说“覆盖”。")
        }
        if (content.length > MAX_TEXT_WRITE_CHARS) {
            return ToolResult.error("单次写入不能超过 $MAX_TEXT_WRITE_CHARS 个字符。")
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }.getOrElse { return errorResult("写入文件失败", it) }
        return ToolResult.success(
            message = "已写入 ${target.absolutePath}（${content.length} 个字符）。",
            spoken = "已写入文件 ${target.name}。",
        )
    }

    fun copyFile(rawSource: String, rawDestination: String): ToolResult {
        val source = resolve(rawSource, mustExist = true)
            ?: return ToolResult.denied("源路径不在 Pilot 可访问的范围内。")
        val destination = resolve(rawDestination, mustExist = false)
            ?: return ToolResult.denied("目标路径不在 Pilot 可访问的范围内。")
        if (!source.isFile) return ToolResult.error("源文件不存在。")
        val finalTarget = if (destination.isDirectory) File(destination, source.name) else destination
        if (!isAllowed(finalTarget)) return ToolResult.denied("目标路径越界，已拒绝。")
        if (!hasAllFilesAccess() && !isInsideAppStorage(finalTarget)) {
            return ToolResult.unavailable("复制到共享存储需要“所有文件访问”权限。")
        }
        runCatching {
            finalTarget.parentFile?.mkdirs()
            source.copyTo(finalTarget, overwrite = false)
        }.getOrElse { return errorResult("复制文件失败", it) }
        return ToolResult.success(
            "已复制到 ${finalTarget.absolutePath}。",
            "已复制文件 ${source.name}。",
        )
    }

    fun moveFile(rawSource: String, rawDestination: String): ToolResult {
        val source = resolve(rawSource, mustExist = true)
            ?: return ToolResult.denied("源路径不在 Pilot 可访问的范围内。")
        val destination = resolve(rawDestination, mustExist = false)
            ?: return ToolResult.denied("目标路径不在 Pilot 可访问的范围内。")
        if (!source.exists()) return ToolResult.error("源文件不存在。")
        val finalTarget = if (destination.isDirectory) File(destination, source.name) else destination
        if (!isAllowed(finalTarget)) return ToolResult.denied("目标路径越界，已拒绝。")
        if (!hasAllFilesAccess() && !isInsideAppStorage(finalTarget)) {
            return ToolResult.unavailable("移动到共享存储需要“所有文件访问”权限。")
        }
        runCatching {
            finalTarget.parentFile?.mkdirs()
            if (!source.renameTo(finalTarget)) {
                source.copyTo(finalTarget, overwrite = false)
                if (!source.delete()) throw IOException("复制成功但源文件删除失败")
            }
        }.getOrElse { return errorResult("移动文件失败", it) }
        return ToolResult.success(
            "已移动到 ${finalTarget.absolutePath}。",
            "已移动文件 ${source.name}。",
        )
    }

    fun deleteFile(rawPath: String): ToolResult {
        val target = resolve(rawPath, mustExist = true)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内。")
        if (target == externalRoot || allowedRoots.any { it == target }) {
            return ToolResult.denied("不能删除根目录或应用保护区。")
        }
        val ok = runCatching {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        }.getOrElse { return errorResult("删除失败", it) }
        if (!ok) return ToolResult.error("系统没有允许删除这个项目。")
        return ToolResult.success(
            "已删除 ${target.absolutePath}。",
            "已删除 ${target.name}。",
        )
    }

    suspend fun takeScreenshot(): ToolResult {
        val reader = readerProvider()
            ?: return ToolResult.unavailable(
                "截屏需要 Pilot 的无障碍操作权限。请先在设置中开启，再试一次。"
            )
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = reader.screenshot(dir)
            ?: return ToolResult.error("系统拒绝了这次截屏，或当前界面不允许截屏。")
        return ToolResult.success(
            "已截屏：${file.absolutePath}",
            "截屏完成，已保存到 Pilot 的截图文件夹。",
        )
    }

    fun openFile(rawPath: String): ToolResult {
        val target = resolve(rawPath, mustExist = true)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内。")
        if (!target.isFile) return ToolResult.error("没有找到这个文件。")
        val uri = uriFor(target) ?: return ToolResult.error("无法为这个文件创建安全访问链接。")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(target))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.success("已请求系统打开 ${target.name}。", "正在打开 ${target.name}。")
        }.getOrElse {
            Dbg.w(TAG, "open failed: ${it.javaClass.simpleName}")
            ToolResult.error("没有应用能打开 ${target.name}。")
        }
    }

    fun shareFile(rawPath: String): ToolResult {
        val target = resolve(rawPath, mustExist = true)
            ?: return ToolResult.denied("这个路径不在 Pilot 可访问的范围内。")
        if (!target.isFile) return ToolResult.error("没有找到这个文件。")
        val uri = uriFor(target) ?: return ToolResult.error("无法为这个文件创建安全访问链接。")
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType(target))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(target.name, uri) }
        val chooser = Intent.createChooser(send, "分享 ${target.name}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            ToolResult.success("已打开分享面板。", "请选择要分享到的应用。")
        }.getOrElse {
            Dbg.w(TAG, "share failed: ${it.javaClass.simpleName}")
            ToolResult.error("无法打开分享面板。")
        }
    }

    fun defaultDirectoryFor(rawHint: String): String {
        val text = rawHint.lowercase(Locale.ROOT)
        val dir = when {
            text.contains("下载") || text.contains("download") ->
                Environment.DIRECTORY_DOWNLOADS
            text.contains("文档") || text.contains("document") ->
                Environment.DIRECTORY_DOCUMENTS
            text.contains("图片") || text.contains("picture") || text.contains("照片") ->
                Environment.DIRECTORY_PICTURES
            text.contains("音乐") || text.contains("music") ->
                Environment.DIRECTORY_MUSIC
            else -> ""
        }
        return if (dir.isBlank()) "" else File(externalRoot, dir).absolutePath
    }

    fun rootDirectory(): String =
        externalRoot?.absolutePath ?: context.filesDir.absolutePath

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun resolve(raw: String, mustExist: Boolean): File? {
        val clean = raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’', ' ', '，', ',', '。', '.')
        if (clean.isBlank() || clean.contains('\u0000')) return null
        if (clean.split(File.separatorChar).any { it == ".." }) return null
        val candidate = when {
            clean == "~/Pilot" -> context.filesDir
            clean.startsWith("~/") -> File(context.filesDir, clean.removePrefix("~/"))
            clean.startsWith("/") -> File(clean)
            else -> {
                val hinted = defaultDirectoryFor(clean).ifBlank {
                    externalRoot?.absolutePath ?: context.filesDir.absolutePath
                }
                File(hinted, clean)
            }
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!isAllowed(canonical)) return null
        if (mustExist && !canonical.exists()) return null
        return canonical
    }

    private fun isAllowed(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return allowedRoots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            path == rootPath || path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
        }
    }

    private fun isInsideAppStorage(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.getExternalCacheDir(),
        ).any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            path == rootPath || path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
        }
    }

    private fun uriFor(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()

    private fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun errorResult(prefix: String, error: Throwable): ToolResult {
        Dbg.w(TAG, "$prefix: ${error.javaClass.simpleName}: ${error.message}")
        return ToolResult.error("$prefix：${error.message?.take(160) ?: error.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "PilotFileTools"
        private const val MAX_READ_BYTES = 512L * 1024L
        private const val MAX_TEXT_WRITE_CHARS = 128 * 1024
        private const val MAX_OUTPUT_CHARS = 12_000
    }
}
