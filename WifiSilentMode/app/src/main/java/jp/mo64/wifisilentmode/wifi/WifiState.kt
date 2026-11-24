package jp.mo64.wifisilentmode.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import jp.mo64.wifisilentmode.data.AppPrefs

object WifiState {
    fun ssid(c: Context): String? = try {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.takeIf { it != "<unknown ssid>" && it != "0x" }?.removeSurrounding("\"")
    } catch (_: SecurityException) { null }
    fun connected(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    fun home(c: Context): Boolean = ssid(c)?.let { it in AppPrefs.ssids(c) } == true
}
