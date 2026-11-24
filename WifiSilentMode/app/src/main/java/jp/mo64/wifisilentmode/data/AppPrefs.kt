package jp.mo64.wifisilentmode.data

import android.content.Context

object AppPrefs {
    private const val FILE = "wifi_silent_prefs"
    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun ssids(c: Context): Set<String> = prefs(c).getStringSet("home_ssids", emptySet())?.toSet() ?: emptySet()
    fun add(c: Context, s: String) { prefs(c).edit().putStringSet("home_ssids", ssids(c) + s).apply() }
    fun remove(c: Context, s: String) { prefs(c).edit().putStringSet("home_ssids", ssids(c) - s).apply() }
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean("enabled", v).apply() }

    fun arrivalEnabled(c: Context) = prefs(c).getBoolean("arrival_enabled", true)
    fun setArrivalEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("arrival_enabled", v).apply()
    }

    fun departureEnabled(c: Context) = prefs(c).getBoolean("departure_enabled", true)
    fun setDepartureEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("departure_enabled", v).apply()
    }
}
