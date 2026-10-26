package com.luca.classmanager.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Copies a user-picked image into app-private storage. No network and no storage permission.
 */
class AttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "attachments").apply { mkdirs() }

    fun import(uri: Uri): String? {
        val extension = extensionFor(uri)
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            destination.absolutePath
        } catch (_: Exception) {
            destination.delete()
            null
        }
    }

    fun read(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val root = directory.canonicalFile
        val target = File(path).canonicalFile
        if (!target.isFile || !target.path.startsWith(root.path + File.separator)) return null
        return target.readBytes()
    }

    fun writeNamed(fileName: String, bytes: ByteArray): String {
        val safe = fileName.substringAfterLast('/').substringAfterLast('\\').replace("..", "")
        require(safe.isNotBlank())
        val destination = File(directory, "restored-${UUID.randomUUID()}-$safe")
        destination.writeBytes(bytes)
        return destination.absolutePath
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val root = directory.canonicalFile
        val target = File(path).canonicalFile
        if (target.isFile && target.path.startsWith(root.path + File.separator)) {
            target.delete()
        }
    }

    private fun extensionFor(uri: Uri): String {
        val type = appContext.contentResolver.getType(uri).orEmpty()
        return when {
            type.endsWith("png") -> "png"
            type.endsWith("webp") -> "webp"
            else -> "jpg"
        }
    }
}
