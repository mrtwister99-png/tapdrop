package cz.tapdrop.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val name: String,
    val size: Long,
    val sent: Boolean,        // true = odesláno do notebooku, false = přijato z notebooku
    val ok: Boolean,
    val time: Long,
    val error: String = "",
    val uri: String = "",     // kam byl soubor uložen (pro otevření)
)

class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    val items = mutableStateListOf<HistoryItem>()

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(
                        HistoryItem(
                            o.getString("name"), o.getLong("size"), o.getBoolean("sent"),
                            o.getBoolean("ok"), o.getLong("time"), o.optString("error"), o.optString("uri"),
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun add(item: HistoryItem) {
        // změny stavu Compose musí jít z hlavního vlákna, ale mutableStateList je thread-safe pro snapshoty
        items.add(0, item)
        while (items.size > 500) items.removeAt(items.size - 1)
        save()
    }

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    private fun save() {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject().put("name", it.name).put("size", it.size).put("sent", it.sent)
                        .put("ok", it.ok).put("time", it.time).put("error", it.error).put("uri", it.uri)
                )
            }
            file.writeText(arr.toString())
        }
    }
}
