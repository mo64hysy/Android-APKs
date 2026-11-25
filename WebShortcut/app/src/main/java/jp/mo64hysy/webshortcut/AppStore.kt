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

data class GlobalUserScript(
    val id: String,
    val name: String,
    val path: String,
    val enabled: Boolean = true
)

object AppStore {
    private const val PREFS = "web_shortcut_store"
    private const val KEY = "entries"
    private const val KEY_GLOBAL_SCRIPTS = "global_user_scripts"
    private const val KEY_TOP_MARGIN = "top_margin_dp"
    private const val KEY_BOTTOM_MARGIN = "bottom_margin_dp"

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

    fun globalScripts(context: Context): MutableList<GlobalUserScript> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_SCRIPTS, "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            GlobalUserScript(o.getString("id"), o.getString("name"), o.getString("path"),
                o.optBoolean("enabled", true))
        }
    }

    fun saveGlobalScript(context: Context, script: GlobalUserScript) {
        val list = globalScripts(context)
        val index = list.indexOfFirst { it.id == script.id }
        if (index >= 0) list[index] = script else list.add(script)
        persistGlobalScripts(context, list)
    }

    fun deleteGlobalScript(context: Context, id: String) {
        persistGlobalScripts(context, globalScripts(context).filterNot { it.id == id })
    }

    private fun persistGlobalScripts(context: Context, scripts: List<GlobalUserScript>) {
        val array = JSONArray()
        scripts.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("path", it.path).put("enabled", it.enabled))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GLOBAL_SCRIPTS, array.toString()).apply()
    }

    fun topMarginDp(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOP_MARGIN, 0)

    fun bottomMarginDp(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BOTTOM_MARGIN, 0)

    fun saveMargins(context: Context, top: Int, bottom: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TOP_MARGIN, top.coerceIn(0, 300))
            .putInt(KEY_BOTTOM_MARGIN, bottom.coerceIn(0, 300))
            .apply()
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
