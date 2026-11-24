package jp.mo64.wifisilentmode.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import jp.mo64.wifisilentmode.data.AppPrefs
import jp.mo64.wifisilentmode.service.MonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AppPrefs.enabled(context)) ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }
}
