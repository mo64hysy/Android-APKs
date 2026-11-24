package jp.mo64.wifisilentmode.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

object WifiState {
    fun hasSsidPermission(c: Context): Boolean {
        val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearbyGranted && locationGranted && backgroundGranted
    }

    fun usableSsid(raw: String?): String? {
        if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>" || raw == "0x") return null
        return raw.removeSurrounding("\"").takeIf { it.isNotBlank() }
    }

    /**
     * SSID is delivered from onCapabilitiesChanged. On Android 12+, the callback
     * must request FLAG_INCLUDE_LOCATION_INFO or WifiInfo fields are redacted.
     */
    fun createNetworkCallback(onStateChanged: (connected: Boolean, ssid: String?) -> Unit): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val info = caps.transportInfo as? WifiInfo
                    onStateChanged(isWifi, if (isWifi) usableSsid(info?.ssid) else null)
                }

                override fun onLost(network: Network) {
                    onStateChanged(false, null)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val info = caps.transportInfo as? WifiInfo
                    onStateChanged(isWifi, if (isWifi) usableSsid(info?.ssid) else null)
                }

                override fun onLost(network: Network) {
                    onStateChanged(false, null)
                }
            }
        }
    }

    fun connected(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
