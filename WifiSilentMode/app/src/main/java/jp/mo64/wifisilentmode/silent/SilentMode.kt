package jp.mo64.wifisilentmode.silent

import android.content.Context
import android.media.AudioManager

object SilentMode {
    fun apply(c: Context, home: Boolean) {
        val a = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target = if (home) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
        if (a.ringerMode != target) try { a.ringerMode = target } catch (_: SecurityException) { }
    }
    fun label(c: Context): String = when ((c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "通常モード"
        AudioManager.RINGER_MODE_VIBRATE -> "バイブレーション"
        else -> "サイレント"
    }
}
