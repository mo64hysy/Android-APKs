package jp.mo64.wifisilentmode.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jp.mo64.wifisilentmode.data.AppPrefs
import jp.mo64.wifisilentmode.silent.SilentMode
import jp.mo64.wifisilentmode.wifi.WifiState

class MonitorService : Service() {
    private lateinit var cm: ConnectivityManager
    private lateinit var callback: ConnectivityManager.NetworkCallback
    private var lastHome: Boolean? = null
    private var ssidUnavailableNoticeShown = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("wifi_auto", "Wi-Fi自動切り替え", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(7, notice("Wi-Fi状態を監視中"))
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback = WifiState.createNetworkCallback { connected, ssid ->
            applyNetworkState(connected, ssid)
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            getSystemService(NotificationManager::class.java).notify(7, notice("Wi-Fi監視を開始できませんでした"))
        }
    }

    private fun applyNetworkState(connected: Boolean, ssid: String?) {
        if (!AppPrefs.enabled(this)) return

        // Never classify an active Wi-Fi connection as "away" when its SSID is unknown.
        if (connected && ssid == null) {
            if (!ssidUnavailableNoticeShown) {
                getSystemService(NotificationManager::class.java).notify(
                    7, notice("SSID取得不可：位置情報の権限と設定を確認してください")
                )
                ssidUnavailableNoticeShown = true
            }
            return
        }

        ssidUnavailableNoticeShown = false
        val home = connected && ssid in AppPrefs.ssids(this)
        if (lastHome != home) {
            lastHome = home
            SilentMode.apply(this, home)
            getSystemService(NotificationManager::class.java).notify(
                7, notice(if (home) "自宅：通常モード" else "外出先：バイブレーション")
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notice(s: String) = NotificationCompat.Builder(this, "wifi_auto")
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle("Wi-Fiマナーモード")
        .setContentText(s)
        .setOngoing(true)
        .build()
}
