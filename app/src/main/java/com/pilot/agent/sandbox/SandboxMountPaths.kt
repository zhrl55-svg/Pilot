package com.pilot.agent.sandbox

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves user-selected folders into host POSIX paths that PRoot can bind.
 *
 * Both the settings UI and PRootKernel must use this resolver. Keeping two
 * implementations previously caused ACTION_OPEN_DOCUMENT_TREE URIs under
 * `/tree/` to be rejected while PRoot already knew how to resolve them.
 */
object SandboxMountPaths {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    sealed interface Resolution {
        data class Success(val hostPath: String) : Resolution

        data class Failure(
            val reason: Reason,
            val detail: String,
        ) : Resolution {
            fun userMessage(): String = when (reason) {
                Reason.UNSUPPORTED_PROVIDER ->
                    "这是第三方文件提供器，没有可 bind 的本地路径。请选择「手机存储」中的目录。"
                Reason.INVALID_URI -> "目录授权信息无法解析：$detail"
                Reason.UNKNOWN_VOLUME -> "找不到保存目录对应的存储卷：$detail"
                Reason.OUTSIDE_VOLUME -> "目录解析到了存储卷之外：$detail"
                Reason.OUTSIDE_SHARED_STORAGE -> "只能挂载共享存储中的目录：$detail"
                Reason.NOT_FOUND -> "目录不存在：$detail"
                Reason.NOT_DIRECTORY -> "所选路径不是目录：$detail"
                Reason.PERMISSION_DENIED -> "没有权限读取目录：$detail"
                Reason.UNREADABLE_DIRECTORY ->
                    "目录存在，但系统不允许列出内容：$detail。请确认已授予 Pilot「所有文件访问」权限。"
            }
        }
    }

    enum class Reason {
        UNSUPPORTED_PROVIDER,
        INVALID_URI,
        UNKNOWN_VOLUME,
        OUTSIDE_VOLUME,
        OUTSIDE_SHARED_STORAGE,
        NOT_FOUND,
        NOT_DIRECTORY,
        PERMISSION_DENIED,
        UNREADABLE_DIRECTORY,
    }

    fun resolveTreeUri(context: Context, treeUri: Uri): Resolution {
        if (treeUri.scheme == "file") {
            val path = treeUri.path
                ?: return Resolution.Failure(Reason.INVALID_URI, treeUri.toString())
            return validateHostPath(context, path)
        }
        if (treeUri.scheme != "content") {
            return Resolution.Failure(Reason.INVALID_URI, treeUri.toString())
        }
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return Resolution.Failure(
                Reason.UNSUPPORTED_PROVIDER,
                treeUri.authority.orEmpty(),
            )
        }

        val documentId = runCatching {
            if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
        }.getOrElse {
            return Resolution.Failure(Reason.INVALID_URI, treeUri.toString())
        }

        val separator = documentId.indexOf(':')
        if (separator <= 0) {
            return Resolution.Failure(Reason.INVALID_URI, documentId)
        }
        val volumeId = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val volumeRoot = resolveVolumeRoot(context, volumeId)
            ?: return Resolution.Failure(Reason.UNKNOWN_VOLUME, volumeId)

        val root = runCatching { volumeRoot.canonicalFile }.getOrElse {
            return Resolution.Failure(Reason.UNKNOWN_VOLUME, volumeRoot.absolutePath)
        }
        val candidate = runCatching {
            if (relativePath.isBlank()) root else File(root, relativePath).canonicalFile
        }.getOrElse {
            return Resolution.Failure(Reason.INVALID_URI, documentId)
        }
        if (!candidate.isWithin(root)) {
            return Resolution.Failure(Reason.OUTSIDE_VOLUME, candidate.absolutePath)
        }
        return validateDirectory(candidate)
    }

    /**
     * Validates a manually entered host path. It must resolve to a readable
     * directory inside shared storage, not merely be a string that starts
     * with `/`.
     */
    fun validateHostPath(context: Context, rawPath: String): Resolution {
        val path = rawPath.trim()
        if (path.isEmpty()) {
            return Resolution.Failure(Reason.INVALID_URI, "路径为空")
        }
        val candidate = runCatching { File(path).canonicalFile }.getOrElse {
            return Resolution.Failure(Reason.INVALID_URI, rawPath)
        }
        if (!candidate.isDirectory) {
            return Resolution.Failure(
                if (candidate.exists()) Reason.NOT_DIRECTORY else Reason.NOT_FOUND,
                candidate.absolutePath,
            )
        }
        if (sharedStorageRoots(context).none { candidate.isWithin(it) }) {
            return Resolution.Failure(Reason.OUTSIDE_SHARED_STORAGE, candidate.absolutePath)
        }
        return validateDirectory(candidate)
    }

    private fun validateDirectory(candidate: File): Resolution {
        if (!candidate.exists()) {
            return Resolution.Failure(Reason.NOT_FOUND, candidate.absolutePath)
        }
        if (!candidate.isDirectory) {
            return Resolution.Failure(Reason.NOT_DIRECTORY, candidate.absolutePath)
        }
        if (!candidate.canRead()) {
            return Resolution.Failure(Reason.PERMISSION_DENIED, candidate.absolutePath)
        }
        if (candidate.list() == null) {
            return Resolution.Failure(Reason.UNREADABLE_DIRECTORY, candidate.absolutePath)
        }
        return Resolution.Success(candidate.absolutePath)
    }

    private fun resolveVolumeRoot(context: Context, volumeId: String): File? =
        if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val storageManager =
                context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            storageManager?.storageVolumes?.firstOrNull {
                it.uuid?.equals(volumeId, ignoreCase = true) == true
            }?.directory
        }

    private fun sharedStorageRoots(context: Context): List<File> {
        val roots = linkedSetOf<File>()
        Environment.getExternalStorageDirectory()?.let(roots::add)
        val storageManager =
            context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        storageManager?.storageVolumes.orEmpty()
            .mapNotNullTo(roots) { it.directory }
        return roots.mapNotNull {
            runCatching { it.canonicalFile }.getOrNull()
        }
    }

    private fun File.isWithin(root: File): Boolean {
        val childPath = canonicalPath
        val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        return childPath == rootPath || childPath.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }
}
