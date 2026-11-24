package jp.mo64.wifisilentmode.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import jp.mo64.wifisilentmode.data.AppPrefs

object WifiState {
    fun hasSsidPermission(c: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun usableSsid(raw: String?): String? {
        if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>" || raw == "0x") return null
        return raw.removeSurrounding("\"").takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    fun ssid(c: Context): String? {
        if (!hasSsidPermission(c)) return null
        return try {
            // Prefer the compatibility API: NetworkCapabilities may redact WifiInfo fields
            // unless a callback explicitly requests FLAG_INCLUDE_LOCATION_INFO.
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            usableSsid(wm.connectionInfo?.ssid)?.let { return it }

            // Fallback for devices where the compatibility API does not return an SSID.
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = network?.let { cm.getNetworkCapabilities(it) }
            val info = capabilities?.transportInfo as? WifiInfo
            usableSsid(info?.ssid)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun connected(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun home(c: Context): Boolean = ssid(c)?.let { it in AppPrefs.ssids(c) } == true
}
