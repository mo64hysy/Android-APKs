package jp.mo64hysy.webshortcut

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WebEntry(
    val id: String,
    val name: String,
    val url: String,
    val iconPath: String = "",
    val scriptPath: String = ""
)

object AppStore {
    private const val PREFS = "web_shortcut_store"
    private const val KEY = "entries"

    fun all(context: Context): MutableList<WebEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            WebEntry(o.getString("id"), o.getString("name"), o.getString("url"),
                o.optString("iconPath"), o.optString("scriptPath"))
        }
    }

    fun save(context: Context, entry: WebEntry) {
        val list = all(context)
        val index = list.indexOfFirst { it.id == entry.id }
        if (index >= 0) list[index] = entry else list.add(entry)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = all(context)
        list.removeAll { it.id == id }
        persist(context, list)
    }

    fun find(context: Context, id: String): WebEntry? = all(context).firstOrNull { it.id == id }

    private fun persist(context: Context, list: List<WebEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("url", it.url).put("iconPath", it.iconPath).put("scriptPath", it.scriptPath))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun copyUri(context: Context, uri: android.net.Uri, fileName: String): String {
        val dir = File(context.filesDir, "entries").apply { mkdirs() }
        val file = File(dir, fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "ファイルを開けません" }.copyTo(file.outputStream())
        }
        return file.absolutePath
    }
}
