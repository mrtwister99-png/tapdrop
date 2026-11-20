package cz.tapdrop.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Endpoint(val host: String, val port: Int)
data class RemoteFile(val id: String, val name: String, val size: Long)
data class DiscoverInfo(val id: String, val name: String, val version: String)

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
