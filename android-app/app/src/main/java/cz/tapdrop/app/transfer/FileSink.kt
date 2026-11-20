package cz.tapdrop.app.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import cz.tapdrop.app.data.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLConnection

/** Cíl pro stahovaný soubor: Downloads/TapDrop (MediaStore) nebo složka vybraná v nastavení (SAF). */
class FileSink(
    val out: OutputStream,
    val uri: String,
    private val onFinish: () -> Unit,
    private val onAbort: () -> Unit,
) {
    fun finish() { runCatching { out.flush(); out.close() }; onFinish() }
    fun abort() { runCatching { out.close() }; onAbort() }

    companion object {
        private fun mime(name: String) =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

        fun open(ctx: Context, prefs: Prefs, name: String): FileSink {
            // 1) uživatelem vybraná složka
            if (prefs.downloadTree.isNotBlank()) {
                val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(prefs.downloadTree))
                if (dir != null && dir.canWrite()) {
                    val f = dir.createFile(mime(name), name) ?: throw java.io.IOException("Nelze vytvořit soubor")
                    val os = ctx.contentResolver.openOutputStream(f.uri, "w")
                        ?: throw java.io.IOException("Nelze zapisovat do složky")
                    return FileSink(os, f.uri.toString(), {}, { runCatching { f.delete() } })
                }
            }
            // 2) Downloads/TapDrop
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openMediaStore(ctx, name)
            else openLegacy(ctx, name)
        }

        private fun openMediaStore(ctx: Context, name: String): FileSink {
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TapDrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Nelze vytvořit soubor ve Stažených")
            val os = cr.openOutputStream(uri) ?: throw java.io.IOException("Nelze zapisovat")
            return FileSink(
                os, uri.toString(),
                onFinish = {
                    val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    cr.update(uri, v, null, null)
                },
                onAbort = { runCatching { cr.delete(uri, null, null) } },
            )
        }

        @Suppress("DEPRECATION")
        private fun openLegacy(ctx: Context, name: String): FileSink {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TapDrop")
            dir.mkdirs()
            var f = File(dir, name)
            var i = 1
            val dot = name.lastIndexOf('.')
            while (f.exists()) {
                val n = if (dot > 0) name.substring(0, dot) + " ($i)" + name.substring(dot) else "$name ($i)"
                f = File(dir, n); i++
            }
            val file = f
            return FileSink(
                FileOutputStream(file), Uri.fromFile(file).toString(),
                onFinish = { MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null) },
                onAbort = { runCatching { file.delete() } },
            )
        }
    }
}
