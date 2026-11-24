package jp.mo64.wifisilentmode.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jp.mo64.wifisilentmode.data.AppPrefs
import jp.mo64.wifisilentmode.silent.SilentMode
import jp.mo64.wifisilentmode.wifi.WifiState

class MonitorService : Service() {
    private lateinit var cm: ConnectivityManager
    private var lastHome: Boolean? = null
    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateMode()
        override fun onLost(network: Network) = updateMode()
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = updateMode()
    }
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("wifi_auto", "Wi-Fi自動切り替え", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, notice("Wi-Fi状態を監視中"))
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) { }
        updateMode()
    }
    private fun updateMode() {
        if (!AppPrefs.enabled(this)) return
        val home = WifiState.connected(this) && WifiState.home(this)
        if (lastHome != home) {
            lastHome = home
            SilentMode.apply(this, home)
            getSystemService(NotificationManager::class.java).notify(7, notice(if (home) "自宅：通常モード" else "外出先：バイブレーション"))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { updateMode(); return START_STICKY }
    override fun onDestroy() { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notice(s: String) = NotificationCompat.Builder(this, "wifi_auto").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle("Wi-Fiマナーモード").setContentText(s).setOngoing(true).build()
}
