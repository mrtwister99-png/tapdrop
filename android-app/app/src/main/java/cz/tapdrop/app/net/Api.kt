package cz.tapdrop.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Endpoint(val host: String, val port: Int)
data class RemoteFile(val id: String, val name: String, val size: Long)
data class DiscoverInfo(val id: String, val name: String, val version: String)

object OdhozPayload {
    fun encode(text: String): String {
        val escaped = buildString {
            for (c in text) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                    }
                }
            }
        }
        return "{\"text\":\"$escaped\"}"
    }

    fun decode(json: String): String {
        val colon = json.indexOf(':')
        if (colon < 0) throw IOException("Neplatná Odhoz odpověď")
        val start = json.indexOf('"', colon + 1)
        if (start < 0) throw IOException("Neplatná Odhoz odpověď")
        val out = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return out.toString()
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= json.length) throw IOException("Neplatná Odhoz odpověď")
            i++
            when (json[i]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 >= json.length) throw IOException("Neplatná Odhoz odpověď")
                    val hex = json.substring(i + 1, i + 5)
                    val code = hex.toIntOrNull(16) ?: throw IOException("Neplatná Odhoz odpověď")
                    out.append(code.toChar())
                    i += 4
                }
                else -> throw IOException("Neplatná Odhoz odpověď")
            }
            i++
        }
        throw IOException("Neplatná Odhoz odpověď")
    }
}

class Api(val ep: Endpoint, private val token: String) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun url(path: String) = "http://${ep.host}:${ep.port}$path"

    fun req(path: String): Request.Builder =
        Request.Builder().url(url(path)).header("X-TapDrop-Token", token)

    /** Zkontroluje HTTP kód; 401 = špatný token. */
    fun check(resp: Response) {
        if (resp.code == 401) throw TapError(ErrorKind.BAD_TOKEN)
    }

    private fun <T> call(r: Request, parse: (String) -> T): T {
        client.newCall(r).execute().use { resp ->
            check(resp)
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return parse(resp.body?.string().orEmpty())
        }
    }

    suspend fun discover(): DiscoverInfo = withContext(Dispatchers.IO) {
        call(req("/discover").build()) {
            val o = JSONObject(it)
            DiscoverInfo(o.getString("id"), o.optString("name"), o.optString("version"))
        }
    }

    suspend fun pending(): List<RemoteFile> = withContext(Dispatchers.IO) {
        call(req("/pending").build()) {
            val a = JSONArray(it)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RemoteFile(o.getString("id"), o.getString("name"), o.getLong("size"))
            }
        }
    }

    suspend fun ack(id: String) = withContext(Dispatchers.IO) {
        val r = req("/ack/$id").post(RequestBody.create("application/json".toMediaType(), "{}")).build()
        call(r) { }
    }

    suspend fun getOdhoz(): String = withContext(Dispatchers.IO) {
        call(req("/odhoz").build()) { OdhozPayload.decode(it) }
    }

    suspend fun setOdhoz(text: String) = withContext(Dispatchers.IO) {
        if (text.toByteArray(Charsets.UTF_8).size > 1_048_576) {
            throw IOException("Text je větší než 1 MiB")
        }
        val body = OdhozPayload.encode(text).toRequestBody("application/json".toMediaType())
        val r = req("/odhoz").put(body).build()
        call(r) { OdhozPayload.decode(it) }
    }

    /** Kolik bajtů už má PC z nedokončeného uploadu (pro navázání po výpadku WiFi). */
    suspend fun uploadOffset(name: String, size: Long): Long = withContext(Dispatchers.IO) {
        val u = okhttp3.HttpUrl.Builder().scheme("http").host(ep.host).port(ep.port)
            .addPathSegments("upload/status").addQueryParameter("name", name)
            .addQueryParameter("size", size.toString()).build()
        call(Request.Builder().url(u).header("X-TapDrop-Token", token).build()) {
            JSONObject(it).optLong("offset", 0)
        }
    }
}
