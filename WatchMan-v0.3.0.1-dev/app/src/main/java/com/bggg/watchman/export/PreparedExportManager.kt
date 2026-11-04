package com.bggg.watchman.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Durable two-phase export: build/validate privately first, then copy to the user's chosen URI. */
class PreparedExportManager(private val context: Context) {
    data class PreparedExport(
        val path: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val pdfPages: Int? = null
    )

    fun prepare(bytes: ByteArray, fileName: String, mimeType: String): PreparedExport {
        require(bytes.isNotEmpty()) { "Export generated zero bytes" }
        val dir = File(context.cacheDir, "prepared_exports").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "${System.currentTimeMillis()}-$safe")
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!file.isFile || file.length() <= 0L) {
            file.delete()
            throw IllegalStateException("Prepared export is empty")
        }
        val pages = if (mimeType == "application/pdf") validatePdf(file) else null
        return PreparedExport(file.absolutePath, fileName, mimeType, file.length(), pages)
    }

    fun restore(path: String?, fileName: String?, mimeType: String?): PreparedExport? {
        if (path.isNullOrBlank() || fileName.isNullOrBlank() || mimeType.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            PreparedExport(file.absolutePath, fileName, mimeType, file.length(), if (mimeType == "application/pdf") validatePdf(file) else null)
        } catch (_: Exception) {
            null
        }
    }

    fun saveToUri(prepared: PreparedExport, resolver: ContentResolver, uri: Uri): Long {
        val source = File(prepared.path)
        if (!source.isFile || source.length() <= 0L) throw IllegalStateException("Prepared export is no longer available")
        resolver.openOutputStream(uri, "w")?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output, 64 * 1024) }
            output.flush()
        } ?: throw IllegalStateException("Could not open selected file")

        val verified = verifyDestination(resolver, uri, prepared.mimeType)
        if (verified <= 0L) throw IllegalStateException("Saved export is empty")
        return verified
    }

    fun delete(prepared: PreparedExport?) {
        prepared?.let { runCatching { File(it.path).delete() } }
    }

    private fun validatePdf(file: File): Int {
        FileInputStream(file).use { input ->
            val header = ByteArray(5)
            if (input.read(header) != 5 || String(header, Charsets.US_ASCII) != "%PDF-") {
                throw IllegalStateException("Generated file is not a valid PDF")
            }
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount <= 0) throw IllegalStateException("Generated PDF has no readable pages")
                return renderer.pageCount
            }
        }
    }

    private fun verifyDestination(resolver: ContentResolver, uri: Uri, mimeType: String): Long {
        var count = 0L
        var header = ByteArray(0)
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var first = true
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                if (first) {
                    header = buffer.copyOf(minOf(n, 5))
                    first = false
                }
                count += n
            }
        } ?: throw IllegalStateException("Could not verify saved export")
        if (mimeType == "application/pdf") {
            if (header.size < 5 || String(header, Charsets.US_ASCII) != "%PDF-") {
                throw IllegalStateException("Saved file is not a valid PDF")
            }
        }
        return count
    }
}
