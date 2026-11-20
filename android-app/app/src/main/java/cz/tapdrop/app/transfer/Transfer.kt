package cz.tapdrop.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import cz.tapdrop.app.data.HistoryItem
import cz.tapdrop.app.data.Prefs
import cz.tapdrop.app.data.HistoryStore
import cz.tapdrop.app.net.Api
import cz.tapdrop.app.net.ErrorKind
import cz.tapdrop.app.net.TapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

object FileMeta {
    fun query(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "soubor"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) {
            size = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)
        }
        return name to size.coerceAtLeast(0)
    }
}

private class StreamBody(
    private val ctx: Context,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val onSent: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        val input: InputStream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Soubor nelze otevřít")
        input.use { ins ->
            var toSkip = offset
            while (toSkip > 0) {
                val s = ins.skip(toSkip)
                if (s <= 0) throw IOException("Nelze přeskočit na pozici $offset")
                toSkip -= s
            }
            val buf = ByteArray(64 * 1024)
            var remaining = length
            var sent = 0L
            while (remaining > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Soubor skončil dřív, než se čekalo")
                sink.write(buf, 0, n)
                remaining -= n
                sent += n
                onSent(sent)
            }
        }
    }
}

class Transfer(private val ctx: Context, private val prefs: Prefs, private val history: HistoryStore) {
    private val tag = "TapDrop.Transfer"
    private var lastPublish = 0L

    private fun publish(u: TransferUi, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastPublish > 120) {
            lastPublish = now
            TransferBus.update(u)
        }
    }

    // ------------------------------------------------------------------ ODESLÁNÍ
    /** Odešle všechny soubory z fronty. Vrací počet úspěšně odeslaných. */
    suspend fun uploadQueue(api: Api): Int {
        val uris = prefs.queue.toList()
        var okCount = 0
        uris.forEachIndexed { i, s ->
            val uri = Uri.parse(s)
            val (name, size) = FileMeta.query(ctx, uri)
            try {
                uploadOne(api, uri, name, size, i + 1, uris.size)
                history.add(HistoryItem(name, size, sent = true, ok = true, time = System.currentTimeMillis()))
                prefs.queue.remove(s); prefs.saveQueue()
                okCount++
            } catch (e: TapError) {
                history.add(HistoryItem(name, size, true, false, System.currentTimeMillis(), e.message ?: ""))
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                history.add(HistoryItem(name, size, true, false, System.currentTimeMillis(), e.message ?: "Chyba"))
                throw TapError(ErrorKind.OTHER, "Odeslání „$name“ selhalo: ${e.message}")
            }
        }
        if (prefs.queue.isEmpty()) prefs.readyToSend = false
        return okCount
    }

    private suspend fun uploadOne(api: Api, uri: Uri, name: String, size: Long, idx: Int, count: Int) {
        var offset = 0L
        var attempts = 0
        val meter = Speedometer()
        fun report(done: Long, force: Boolean = false) {
            val (sp, eta) = meter.sample(done, size)
            publish(TransferUi(true, true, "Odesílám", name, idx, count, done, size, sp, eta), force)
        }
        report(0, true)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (attempts > 0) {
                    offset = api.uploadOffset(name, size).coerceIn(0, size)
                    Log.i(tag, "Navazuji upload $name od $offset")
                }
                val body = StreamBody(ctx, uri, offset, size - offset) { sent -> report(offset + sent) }
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, body).build()
                val req = api.req("/upload")
                    .header("X-TapDrop-Size", size.toString())
                    .header("X-TapDrop-Offset", offset.toString())
                    .post(mp).build()
                val (code, text) = withContext(Dispatchers.IO) {
                    api.client.newCall(req).execute().use { r ->
                        api.check(r)
                        r.code to r.body?.string().orEmpty()
                    }
                }
                when (code) {
                    200 -> { report(size, true); return }
                    409 -> {
                        offset = runCatching { JSONObject(text).getLong("offset") }.getOrDefault(0)
                        throw IOException("Nesouhlasí pozice na PC")
                    }
                    else -> throw IOException("PC odpovědělo HTTP $code")
                }
            } catch (e: TapError) {
                throw e
            } catch (e: IOException) {
                attempts++
                Log.w(tag, "Upload $name: pokus $attempts: ${e.message}")
                if (attempts > 6) throw TapError(ErrorKind.NOT_FOUND, "Spojení s notebookem se přerušilo")
                delay(1000L * attempts)
            }
        }
    }

    // ------------------------------------------------------------------ STAŽENÍ
    /** Stáhne všechno z fronty na PC, po každém souboru pošle /ack. Vrací počet stažených. */
    suspend fun downloadAll(api: Api): Int {
        val list = api.pending()
        var okCount = 0
        list.forEachIndexed { i, item ->
            val sink = FileSink.open(ctx, prefs, item.name)
            try {
                downloadOne(api, item.id, item.name, item.size, sink, i + 1, list.size)
                sink.finish()
            } catch (e: Throwable) {
                sink.abort()
                history.add(
                    HistoryItem(item.name, item.size, false, false, System.currentTimeMillis(), (e as? Exception)?.message ?: "Chyba")
                )
                if (e is TapError || e is kotlinx.coroutines.CancellationException) throw e
                throw TapError(ErrorKind.OTHER, "Stažení „${item.name}“ selhalo: ${e.message}")
            }
            history.add(HistoryItem(item.name, item.size, false, true, System.currentTimeMillis(), uri = sink.uri))
            okCount++
            // potvrzení smaže soubor z fronty na PC; zkusíme víckrát
            var acked = false
            for (t in 1..4) {
                try { api.ack(item.id); acked = true; break } catch (e: TapError) { throw e }
                catch (e: Exception) { delay(500L * t) }
            }
            if (!acked) Log.w(tag, "ACK pro ${item.id} se nepodařil")
        }
        return okCount
    }

    private suspend fun downloadOne(api: Api, id: String, name: String, size: Long, sink: FileSink, idx: Int, count: Int) {
        var written = 0L
        var attempts = 0
        val meter = Speedometer()
        fun report(force: Boolean = false) {
            val (sp, eta) = meter.sample(written, size)
            publish(TransferUi(true, false, "Stahuji", name, idx, count, written, size, sp, eta), force)
        }
        report(true)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val rb = api.req("/download/$id")
                if (written > 0) rb.header("Range", "bytes=$written-")
                withContext(Dispatchers.IO) {
                    api.client.newCall(rb.build()).execute().use { r ->
                        api.check(r)
                        if (r.code == 416 && written >= size) return@use
                        if (!r.isSuccessful) throw IOException("PC odpovědělo HTTP ${r.code}")
                        if (written > 0 && r.code != 206) throw TapError(ErrorKind.OTHER, "PC neumí navázat přerušený přenos")
                        val ins = r.body?.byteStream() ?: throw IOException("Prázdná odpověď")
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            sink.out.write(buf, 0, n)
                            written += n
                            report()
                        }
                    }
                }
                if (written >= size) { report(true); return }
                throw IOException("Přenos skončil předčasně ($written/$size)")
            } catch (e: TapError) {
                throw e
            } catch (e: IOException) {
                attempts++
                Log.w(tag, "Download $name: pokus $attempts: ${e.message}")
                if (attempts > 6) throw TapError(ErrorKind.NOT_FOUND, "Spojení s notebookem se přerušilo")
                delay(1000L * attempts)
            }
        }
    }
}
