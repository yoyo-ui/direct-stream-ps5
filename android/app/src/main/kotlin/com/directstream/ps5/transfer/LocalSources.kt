package com.directstream.ps5.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.directstream.ps5.core.engine.SourceSpec
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Turns a Storage Access Framework Uri into an engine source. Content is always streamed; the file is never
 * read into memory or copied. Resume is only offered when the provider gives a seekable descriptor.
 * The identity string mirrors the original project's local fingerprint (mtime + hash of head and tail).
 */
object LocalSources {
    data class Info(val name: String, val size: Long?, val lastModified: Long)

    fun describe(context: Context, uri: Uri): Info {
        var name = "file.bin"; var size: Long? = null; var mtime = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it).takeIf { s -> s >= 0 } }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { mtime = c.getLong(it) }
            }
        }
        return Info(name, size, mtime)
    }

    fun isSeekable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.let { ch -> ch.position(0); ch.size() >= 0 }
        } ?: false
    } catch (e: Exception) { false }

    fun open(context: Context, uri: Uri): SourceSpec.Local {
        val info = describe(context, uri)
        val seekable = isSeekable(context, uri)
        val identity = if (seekable && info.size != null) fingerprint(context, uri, info.size)?.let { "${info.size}:${info.lastModified}:$it" } else null
        val appCtx = context.applicationContext
        return SourceSpec.Local(info.name, info.size, identity, seekable) { offset -> openAt(appCtx, uri, offset, seekable) }
    }

    private fun openAt(context: Context, uri: Uri, offset: Long, seekable: Boolean): InputStream {
        if (!seekable) return context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open the selected file")
        return try {
            val fis = FileInputStream(pfd.fileDescriptor)
            if (offset > 0) fis.channel.position(offset)
            object : FilterInputStream(fis) {
                override fun close() { try { super.close() } finally { pfd.close() } }
            }
        } catch (e: Exception) { pfd.close(); throw e }
    }

    private fun fingerprint(context: Context, uri: Uri, size: Long): String? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val md = MessageDigest.getInstance("SHA-256")
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            ch.position(0); ch.read(buf); md.update(buf.array(), 0, buf.position())
            buf.clear(); ch.position(maxOf(0L, size - 64 * 1024)); ch.read(buf); md.update(buf.array(), 0, buf.position())
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    fun hasPersistedPermission(context: Context, uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
}
