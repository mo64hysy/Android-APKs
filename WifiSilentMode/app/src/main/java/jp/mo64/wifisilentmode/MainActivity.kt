package jp.mo64.wifisilentmode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.mo64.wifisilentmode.data.AppPrefs
import jp.mo64.wifisilentmode.service.MonitorService
import jp.mo64.wifisilentmode.silent.SilentMode
import jp.mo64.wifisilentmode.wifi.WifiState

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty()) permissionRequest.launch(required.toTypedArray())
        setContent { WifiSilentScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiSilentScreen(context: Context) {
    var enabled by remember { mutableStateOf(AppPrefs.enabled(context)) }
    var saved by remember { mutableStateOf(AppPrefs.ssids(context).toList().sorted()) }
    var refresh by remember { mutableIntStateOf(0) }
    val connected = remember(refresh) { WifiState.connected(context) }
    val currentSsid = remember(refresh) { if (connected) WifiState.ssid(context) else null }
    val isHome = currentSsid != null && currentSsid in saved

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Wi-Fiマナーモード") }) }) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("現在の状態", style = MaterialTheme.typography.titleMedium)
                        Text("Wi-Fi：${currentSsid ?: if (connected) "SSID取得不可" else "未接続"}")
                        Text("判定：${if (isHome) "自宅" else "外出先"}")
                        Text("マナーモード：${SilentMode.label(context)}")
                        Text("自動化：${if (enabled) "ON" else "OFF"}")
                        OutlinedButton(onClick = { refresh++ }) { Text("状態を更新") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自動切り替え")
                    Switch(checked = enabled, onCheckedChange = { value ->
                        enabled = value
                        AppPrefs.setEnabled(context, value)
                        val service = Intent(context, MonitorService::class.java)
                        if (value) ContextCompat.startForegroundService(context, service)
                        else context.stopService(service)
                    })
                }
                Text("自宅Wi-Fi", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        currentSsid?.let {
                            AppPrefs.add(context, it)
                            saved = AppPrefs.ssids(context).toList().sorted()
                            refresh++
                        }
                    },
                    enabled = currentSsid != null
                ) { Text("現在のWi-Fiを自宅として登録") }
                if (saved.isEmpty()) Text("登録済みWi-Fiはありません")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(saved) { name ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, modifier = Modifier.weight(1f).padding(top = 10.dp))
                            TextButton(onClick = {
                                AppPrefs.remove(context, name)
                                saved = AppPrefs.ssids(context).toList().sorted()
                                refresh++
                            }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }
}
