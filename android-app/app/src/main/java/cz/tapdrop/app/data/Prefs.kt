package cz.tapdrop.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Delegát: hodnota je Compose stav (UI se překreslí) a zároveň se ukládá do SharedPreferences. */
private class PrefState<T>(
    private val sp: SharedPreferences,
    private val key: String,
    private val def: T,
) : ReadWriteProperty<Any?, T> {
    private var state by mutableStateOf(read())

    @Suppress("UNCHECKED_CAST")
    private fun read(): T = when (def) {
        is String -> sp.getString(key, def) as T
        is Boolean -> sp.getBoolean(key, def) as T
        is Int -> sp.getInt(key, def) as T
        else -> def
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state = value
        sp.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            }
        }.apply()
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE)

    // --- párování
    var host by PrefState(sp, "host", "")
    var port by PrefState(sp, "port", 8765)
    var notebookId by PrefState(sp, "notebook_id", "")
    var token by PrefState(sp, "token", "")

    // --- nastavení
    var autoDownload by PrefState(sp, "auto_download", true)
    var notifications by PrefState(sp, "notifications", true)
    var sound by PrefState(sp, "sound", false)
    /** Stromové URI složky vybrané přes SAF; prázdné = Downloads/TapDrop */
    var downloadTree by PrefState(sp, "download_tree", "")
    var downloadTreeName by PrefState(sp, "download_tree_name", "")

    // --- fronta k odeslání
    var keepAlive by PrefState(sp, "keep_alive", true)
    val queue = mutableStateListOf<String>().also { list ->
        runCatching {
            val arr = JSONArray(sp.getString("queue", "[]"))
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        }
    }

    val paired: Boolean get() = host.isNotBlank() && token.isNotBlank() && notebookId.isNotBlank()

    fun saveQueue() {
        val arr = JSONArray()
        queue.forEach { arr.put(it) }
        sp.edit().putString("queue", arr.toString()).apply()
    }

    fun savePairing(host: String, port: Int, id: String, token: String) {
        this.host = host
        this.port = port
        this.notebookId = id
        this.token = token
    }

    fun clearPairing() {
        host = ""; token = ""; notebookId = ""
    }
}
