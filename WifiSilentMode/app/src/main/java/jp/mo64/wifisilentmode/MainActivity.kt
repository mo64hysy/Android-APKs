package jp.mo64.wifisilentmode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var wifiPermissionGranted by mutableStateOf(false)

    private fun updatePermissionState() {
        wifiPermissionGranted = WifiState.hasSsidPermission(this)
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionState()
        setContent {
            WifiSilentScreen(
                context = this,
                permissionGranted = wifiPermissionGranted,
                onRequestPermission = { requestWifiPermissions() },
                onOpenSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
                onOpenLocationSettings = {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            )
        }
        requestWifiPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun requestWifiPermissions() {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (requested.isNotEmpty()) permissionRequest.launch(requested.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiSilentScreen(
    context: Context,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    var enabled by remember { mutableStateOf(AppPrefs.enabled(context)) }
    var arrivalEnabled by remember { mutableStateOf(AppPrefs.arrivalEnabled(context)) }
    var departureEnabled by remember { mutableStateOf(AppPrefs.departureEnabled(context)) }
    var saved by remember { mutableStateOf(AppPrefs.ssids(context).toList().sorted()) }
    var connected by remember { mutableStateOf(WifiState.connected(context)) }
    var currentSsid by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context, permissionGranted) {
        if (permissionGranted) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = WifiState.createNetworkCallback { isWifi, ssid ->
                connected = isWifi
                currentSsid = ssid
            }
            try {
                cm.registerDefaultNetworkCallback(callback)
            } catch (_: Exception) {
                currentSsid = null
            }
            onDispose {
                try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }
            }
        } else {
            currentSsid = null
            onDispose { }
        }
    }

    val isHome = currentSsid?.let { it in saved }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Wi-Fiマナーモード") }) }) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("現在の状態", style = MaterialTheme.typography.titleMedium)
                        Text("Wi-Fi：${currentSsid ?: if (connected) "SSID取得中／取得不可" else "未接続"}")
                        Text("判定：${when { !connected -> "外出先"; isHome == true -> "自宅"; isHome == false -> "外出先"; else -> "判定保留" }}")
                        Text("マナーモード：${SilentMode.label(context)}")
                        Text("自動化：${if (enabled) "ON" else "OFF"}")
                    }
                }
                if (!permissionGranted) {
                    Text("アプリを閉じてもSSIDを取得するには、位置情報を「常に許可」にする必要があります。位置情報そのものを取得・保存することはありません。下の「アプリの権限設定を開く」から位置情報の許可を変更してください。")
                    Button(onClick = onRequestPermission) { Text("必要な権限を許可") }
                    OutlinedButton(onClick = onOpenSettings) { Text("アプリの権限設定を開く") }
                } else if (connected && currentSsid == null) {
                    Text("SSIDを取得できません。端末の位置情報設定がONになっているか確認してください。位置情報そのものは取得しません。")
                    OutlinedButton(onClick = onOpenLocationSettings) { Text("位置情報の設定を開く") }
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
                HorizontalDivider()
                Text("切り替え動作", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("家に入った時")
                        Text("通常モードに切り替え", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = arrivalEnabled, onCheckedChange = {
                        arrivalEnabled = it
                        AppPrefs.setArrivalEnabled(context, it)
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("家から出た時")
                        Text("バイブレーションに切り替え", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = departureEnabled, onCheckedChange = {
                        departureEnabled = it
                        AppPrefs.setDepartureEnabled(context, it)
                    })
                }
                Text("自宅Wi-Fi", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        currentSsid?.let {
                            AppPrefs.add(context, it)
                            saved = AppPrefs.ssids(context).toList().sorted()
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
                            }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }
}
