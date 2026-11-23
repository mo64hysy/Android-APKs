package jp.parmo64.keymouse

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.Executor

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var surface: KeyMouseView
    private var hid: BluetoothHidDevice? = null
    private var connected: BluetoothDevice? = null
    private var registered = false
    private var reconnectAttempted = false
    private val heldKeys = linkedSetOf<Int>()
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    private val executor = Executor { it.run() }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            registerHid()
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
            connected = null
            refreshStatus()
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, plugged: Boolean) {
            registered = plugged
            if (plugged) reconnectSavedDevice()
            refreshStatus()
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) connected = device
            if (state == BluetoothProfile.STATE_DISCONNECTED && connected == device) connected = null
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        prefs = getSharedPreferences("keymouse", Context.MODE_PRIVATE)
        surface = KeyMouseView(this, "60%",
            { openSettings() }, { usage, down -> sendKey(usage, down) }, { buttons, dx, dy, wheel -> sendMouse(buttons, dx, dy, wheel) },
            prefs.getInt("mouse_sensitivity", 8))
        setContentView(surface)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        } else startHid()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startHid()
        else toast("Bluetooth接続権限が必要です")
    }

    private fun startHid() {
        val a = adapter
        if (a == null || !a.isEnabled) { toast("Bluetoothをオンにしてください"); return }
        a.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
    }

    private fun reconnectSavedDevice() {
        if (reconnectAttempted) return
        reconnectAttempted = true
        val address = prefs.getString("last_device_address", null) ?: return
        try {
            val target = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: return
            hid?.connect(target)
        } catch (_: SecurityException) { toast("Bluetooth権限がありません") }
    }

    private fun registerHid() {
        val descriptor = intArrayOf(
            0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,
            0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x01,
            0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,0x95,0x01,0x75,0x03,0x91,0x01,
            0x95,0x06,0x75,0x08,0x15,0x00,0x25,0xA4,0x05,0x07,0x19,0x00,0x29,0xA4,0x81,0x00,0xC0,
            0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x02,0x09,0x01,0xA1,0x00,0x05,0x09,
            0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,0x95,0x01,0x75,0x05,0x81,0x01,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0
        ).map { it.toByte() }.toByteArray()
        val sdp = BluetoothHidDeviceAppSdpSettings("KeyMouse", "Bluetooth Keyboard and Mouse", "Parmo64", BluetoothHidDevice.SUBCLASS1_COMBO, descriptor)
        try { hid?.registerApp(sdp, null, null, executor, callback) } catch (_: SecurityException) { toast("Bluetooth権限がありません") }
    }

    private fun sendKey(usage: Int, down: Boolean) {
        val device = connected ?: return
        val service = hid ?: return
        try {
            if (down) heldKeys.add(usage) else heldKeys.remove(usage)
            val report = ByteArray(8)
            heldKeys.filter { it in 0xE0..0xE7 }.forEach { report[0] = (report[0].toInt() or (1 shl (it - 0xE0))).toByte() }
            heldKeys.filter { it !in 0xE0..0xE7 }.take(6).forEachIndexed { index, code -> report[index + 2] = code.toByte() }
            service.sendReport(device, 1, report)
        } catch (_: SecurityException) { toast("Bluetooth権限を確認してください") }
    }

    private fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = connected ?: return
        try {
            hid?.sendReport(device, 2, byteArrayOf(
                buttons.toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            ))
        } catch (_: SecurityException) { toast("Bluetooth権限を確認してください") }
    }

    private fun refreshStatus() { if (::surface.isInitialized) surface.status = if (connected != null) "接続済み" else if (registered) "待機中" else "未接続"; if (::surface.isInitialized) surface.invalidate() }

    private fun openSettings() {
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 20, 32, 8)
        }
        val label = android.widget.TextView(this).apply {
            text = "マウス移動の感度：" + surface.mouseSensitivity
            textSize = 16f
        }
        content.addView(label)
        val seek = android.widget.SeekBar(this).apply {
            max = 19
            progress = surface.mouseSensitivity - 1
        }
        content.addView(seek)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                surface.mouseSensitivity = progress + 1
                label.text = "マウス移動の感度：" + surface.mouseSensitivity
                prefs.edit().putInt("mouse_sensitivity", surface.mouseSensitivity).apply()
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("KeyMouse 設定")
            .setView(content)
            .setNeutralButton("接続先を選択") { _, _ -> chooseDevice() }
            .setNegativeButton("閉じる", null).show()
    }

    private fun chooseDevice() {
        val a = adapter ?: return
        try {
            val devices = a.bondedDevices.toList()
            if (devices.isEmpty()) { toast("ペアリング済み端末がありません。Bluetooth設定からペアリングしてください"); return }
            val labels = devices.map { "${it.name ?: "不明な端末"}\n${it.address}" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("接続先")
                .setItems(labels) { _, which ->
                    val target = devices[which]
                    try {
                        prefs.edit().putString("last_device_address", target.address).apply()
                        reconnectAttempted = true
                        hid?.connect(target) ?: toast("HIDサービスを準備中です")
                    } catch (_: SecurityException) { toast("Bluetooth権限がありません") }
                }.setNegativeButton("キャンセル", null).show()
        } catch (_: SecurityException) { toast("Bluetooth権限がありません") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        try { if (registered) hid?.unregisterApp(); adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        super.onDestroy()
    }
}

private data class KeyCap(val label: String, val usage: Int, val weight: Float = 1f, val shiftLabel: String? = null)

private class KeyMouseView(
    context: Context,
    var layoutName: String,
    private val settings: () -> Unit,
    private val key: (Int, Boolean) -> Unit,
    private val mouse: (Int, Int, Int, Int) -> Unit,
    initialMouseSensitivity: Int
) : View(context) {
    var mouseSensitivity = initialMouseSensitivity.coerceIn(1, 20)
    var status = "未接続"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private data class HitBox(val rect: android.graphics.RectF, val cap: KeyCap)
    private val hitBoxes = mutableListOf<HitBox>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pointerKeys = mutableMapOf<Int, Int>()
    private val pressedUsages = mutableSetOf<Int>()
    private val pendingTapReleases = mutableMapOf<Int, Runnable>()
    private var pressedPadButton = 0
    private var padPressed = false
    private var mouseCandidateId = -1
    private var mouseCandidateX = 0f
    private var mouseCandidateY = 0f
    // 右端1/3でキー入力かマウス操作か判定中の、キー押下エフェクト用
    private var mouseCandidatePreviewUsage = 0
    private var mouseLastX = 0f
    private var mouseLastY = 0f
    private var mouseMode = false
    private var mouseButtons = 0
    private var scrollRemainder = 0f
    private val mouseThreshold = 4f
    private val mouseLongPress = Runnable {
        if (mouseCandidateId != -1 && !mouseMode) activateMouseMode()
    }
    private val mouseGraceRunnable = Runnable {
        if (mouseCandidateId == -1) {
            if (mouseButtons != 0) mouse(0, 0, 0, 0)
            mouseButtons = 0
            mouseMode = false
            // マウス操作用の一時エフェクトを残さず、実際に押されているキーだけ表示する。
            pressedUsages.retainAll(pointerKeys.values.toSet())
            invalidate()
        }
    }
    private val mouseModifierUsages = setOf(0xE2, 0xE6, 0x8B, 0xE3)
    // JIS 60%: 5段、テンキー・独立カーソルキーなし
    private val rows = listOf(
        listOf(
            KeyCap("半/全", 0x35, 1.5f), KeyCap("1",30, shiftLabel="!"), KeyCap("2",31, shiftLabel="\""), KeyCap("3",32, shiftLabel="#"),
            KeyCap("4",33, shiftLabel="$"), KeyCap("5",34, shiftLabel="%"), KeyCap("6",35, shiftLabel="&"), KeyCap("7",36, shiftLabel="'"), KeyCap("8",37, shiftLabel="("),
            KeyCap("9",38, shiftLabel=")"), KeyCap("0",39, shiftLabel=""), KeyCap("-",45, shiftLabel="="), KeyCap("^",46, shiftLabel="~"), KeyCap("¥",0x89, shiftLabel="|"), KeyCap("⌫",42,1.7f)
        ),
        listOf(
            KeyCap("Tab",43,1.5f), KeyCap("Q",20), KeyCap("W",26), KeyCap("E",8), KeyCap("R",21),
            KeyCap("T",23), KeyCap("Y",28), KeyCap("U",24), KeyCap("I",12), KeyCap("O",18),
            KeyCap("P",19), KeyCap("@",47, shiftLabel="`"), KeyCap("[",48, shiftLabel="{"), KeyCap("]",49,1.5f, shiftLabel="}")
        ),
        listOf(
            KeyCap("英数",0x91,1.7f), KeyCap("A",4), KeyCap("S",22), KeyCap("D",7), KeyCap("F",9),
            KeyCap("G",10), KeyCap("H",11), KeyCap("J",13), KeyCap("K",14), KeyCap("L",15),
            KeyCap(";",51, shiftLabel="+"), KeyCap(":",52, shiftLabel="*"), KeyCap("Enter",40,2.0f)
        ),
        listOf(
            KeyCap("Shift",0xE1,2.1f), KeyCap("Z",29), KeyCap("X",27), KeyCap("C",6), KeyCap("V",25),
            KeyCap("B",5), KeyCap("N",17), KeyCap("M",16), KeyCap(",",54, shiftLabel="<"), KeyCap(".",55, shiftLabel=">"),
            KeyCap("/",56, shiftLabel="?"), KeyCap("Shift",0xE5,2.1f)
        ),
        listOf(
            KeyCap("Ctrl",0xE0,1.35f), KeyCap("Win",0xE3,1.15f), KeyCap("Alt",0xE2,1.15f),
            KeyCap("無変換",0x8B,1.5f), KeyCap("Space",44,4.7f), KeyCap("変換",0x8A,1.5f),
            KeyCap("かな",0x90,1.35f), KeyCap("Alt",0xE6,1.0f), KeyCap("Ctrl",0xE4,1.25f)
        )
    )

    private fun hasPad() = false
    private fun padWidth() = 0f
    private fun padStart() = width * 2f / 3f
    private fun keyboardLeft() = 12f
    private fun keyboardWidth() = width - 24f
    private fun isInPad(x: Float) = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(25, 28, 34))
        hitBoxes.clear()
        val keyTop = 48f
        val keyLeft = keyboardLeft()
        val availableWidth = keyboardWidth()
        drawKeyboard(canvas, keyLeft, keyTop, availableWidth, height - 70f, null)
        if (hasPad()) {
            val px = padStart()
            val pw = padWidth()
            paint.color = if (padPressed) Color.rgb(43, 49, 59) else Color.rgb(65, 72, 84)
            canvas.drawRoundRect(px, 54f, px + pw, height - 92f, 18f, 18f, paint)
            paint.color = Color.WHITE
            paint.textSize = 16f
            val title = "トラックパッド"
            canvas.drawText(title, px + (pw - paint.measureText(title)) / 2f, height / 2f, paint)
            val bw = pw / 3f
            val labels = listOf("左", "スクロール", "右")
            for (i in 0..2) {
                val lx = px + i * bw
                paint.color = if (pressedPadButton == i + 1) Color.rgb(35, 40, 49) else Color.rgb(90, 98, 112)
                canvas.drawRoundRect(lx + 2f, height - 82f, lx + bw - 2f, height - 24f, 8f, 8f, paint)
                paint.color = Color.WHITE
                paint.textSize = 11f
                canvas.drawText(labels[i], lx + (bw - paint.measureText(labels[i])) / 2f, height - 47f, paint)
            }
        }
        paint.color = Color.rgb(55, 65, 80)
        canvas.drawRoundRect(12f, 8f, 110f, 40f, 8f, 8f, paint)
        paint.color = Color.WHITE
        paint.textSize = 16f
        canvas.drawText("⚙ 設定", 22f, 30f, paint)
        paint.textSize = 14f
        canvas.drawText(status, width - 100f, 28f, paint)
    }

    private fun addSpecialKey(canvas: Canvas, label: String, usage: Int, rect: android.graphics.RectF) {
        paint.color = if (usage in pressedUsages) Color.rgb(35, 39, 47) else Color.rgb(72, 79, 91)
        canvas.drawRoundRect(rect, 4f, 4f, paint)
        paint.color = Color.WHITE
        paint.textSize = 12f
        canvas.drawText(label, rect.centerX() - paint.measureText(label) / 2f, rect.centerY() + 4f, paint)
        hitBoxes.add(HitBox(rect, KeyCap(label, usage)))
    }

    private fun drawArrowCluster(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val gap = 3f
        val cw = (w - gap * 2f) / 3f
        val ch = (h - gap) / 2f
        addSpecialKey(canvas, "↑", 82, android.graphics.RectF(x + cw + gap, y, x + cw * 2f + gap, y + ch))
        addSpecialKey(canvas, "←", 80, android.graphics.RectF(x, y + ch + gap, x + cw, y + h))
        addSpecialKey(canvas, "↓", 81, android.graphics.RectF(x + cw + gap, y + ch + gap, x + cw * 2f + gap, y + h))
        addSpecialKey(canvas, "→", 79, android.graphics.RectF(x + (cw + gap) * 2f, y + ch + gap, x + w, y + h))
    }

    private fun drawNavigationCluster(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val groups = listOf(listOf("Ins" to 73, "Home" to 74, "PgUp" to 75), listOf("Del" to 76, "End" to 77, "PgDn" to 78))
        val gap = 3f
        val cw = (w - gap * 2f) / 3f
        val ch = (h - 70f) / 5f
        groups.forEachIndexed { row, group -> group.forEachIndexed { col, item ->
            val lx = x + col * (cw + gap)
            val ty = y + row * (ch + gap)
            addSpecialKey(canvas, item.first, item.second, android.graphics.RectF(lx, ty, lx + cw, ty + ch))
        } }
        drawArrowCluster(canvas, x, y + h - 58f, w, 54f)
    }

    private fun drawKeyboard(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, leftSide: Boolean? = null) {
        val rowGap = 6f
        val keyGap = 3f
        val keyH = (h - rowGap * 4f) / 5f
        rows.forEachIndexed { rowIndex, originalRow ->
            val row = if (leftSide == null) originalRow else splitRow(originalRow, rowIndex, leftSide)
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val available = w - keyGap * (row.size - 1)
            var currentX = x
            val top = y + rowIndex * (keyH + rowGap)
            row.forEach { cap ->
                val keyW = available * cap.weight / totalWeight
                val rect = android.graphics.RectF(currentX, top, currentX + keyW, top + keyH)
                paint.color = if (cap.usage in pressedUsages && cap.usage != 0) Color.rgb(35, 39, 47) else Color.rgb(72, 79, 91)
                canvas.drawRoundRect(rect, 5f, 5f, paint)
                paint.color = Color.WHITE
                paint.textSize = (keyH * 0.27f).coerceIn(10f, 20f)
                val labelWidth = paint.measureText(cap.label)
                canvas.drawText(cap.label, rect.centerX() - labelWidth / 2f, rect.centerY() + paint.textSize * 0.34f, paint)
                if (!cap.shiftLabel.isNullOrEmpty()) {
                    paint.textSize = (keyH * 0.13f).coerceIn(7f, 11f)
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(cap.shiftLabel, rect.right - 4f, rect.top + paint.textSize + 3f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                hitBoxes.add(HitBox(rect, cap))
                currentX += keyW + keyGap
            }
        }
    }

    private fun splitRow(row: List<KeyCap>, rowIndex: Int, left: Boolean): List<KeyCap> {
        val splitAfter = when (rowIndex) {
            0 -> row.indexOfFirst { it.label == "6" } + 1
            1 -> row.indexOfFirst { it.label == "T" } + 1
            2 -> row.indexOfFirst { it.label == "G" } + 1
            3 -> row.indexOfFirst { it.label == "B" } + 1
            else -> row.indexOfFirst { it.label == "Space" }
        }.coerceIn(1, row.size - 1)
        if (rowIndex != 4) return if (left) row.take(splitAfter) else row.drop(splitAfter)
        val space = row.first { it.label == "Space" }
        return if (left) row.take(splitAfter) + KeyCap("Space", space.usage, 2.2f)
        else listOf(KeyCap("Space", space.usage, 2.2f)) + row.drop(splitAfter + 1)
    }

    private fun isMouseModifier(usage: Int) = usage == 0xE2 || usage == 0xE6 || usage == 0x8B || usage == 0xE3

    private fun addPointerKey(pointerId: Int, x: Float, y: Float) {
        if (y < 44f && x < 120f) return
        val usage = hitBoxes.firstOrNull { it.rect.contains(x, y) }?.cap?.usage ?: return
        if (mouseMode && !isMouseModifier(usage)) return
        if (usage == 0) return
        pointerKeys[pointerId] = usage
        pressedUsages.add(usage)
        if (!(mouseMode && isMouseModifier(usage))) key(usage, true)
        updateMouseButtons()
        invalidate()
    }

    private fun removePointerKey(pointerId: Int) {
        val usage = pointerKeys.remove(pointerId) ?: return
        if (pointerKeys.values.none { it == usage }) {
            pressedUsages.remove(usage)
            if (!(mouseMode && isMouseModifier(usage))) key(usage, false)
            updateMouseButtons()
        }
        invalidate()
    }

    // 右側1/3のタップはマウスジェスチャーとの判定後に送るため、
    // 押下と解放の間隔を確保して短いタップもHID側で取りこぼさないようにする。
    private fun tapKey(usage: Int) {
        pendingTapReleases.remove(usage)?.let {
            handler.removeCallbacks(it)
            if (pointerKeys.values.none { held -> held == usage }) key(usage, false)
        }
        pressedUsages.add(usage)
        key(usage, true)
        invalidate()
        val release = Runnable {
            if (pointerKeys.values.none { held -> held == usage }) {
                pressedUsages.remove(usage)
                key(usage, false)
            }
            pendingTapReleases.remove(usage)
            invalidate()
        }
        pendingTapReleases[usage] = release
        handler.postDelayed(release, 55L)
    }

    private fun activateMouseMode() {
        if (mouseCandidateId == -1 || mouseMode) return
        mouseMode = true
        handler.removeCallbacks(mouseLongPress)
        // マウス操作に切り替わったら通常キーのプレビュー表示を解除する。
        // 修飾キー上から開始した場合は、その指をクリック用修飾キーとして保持する。
        if (mouseCandidatePreviewUsage != 0 && isMouseModifier(mouseCandidatePreviewUsage)) {
            pointerKeys[mouseCandidateId] = mouseCandidatePreviewUsage
        } else if (mouseCandidatePreviewUsage != 0) {
            pressedUsages.remove(mouseCandidatePreviewUsage)
        }
        // 通常キー入力は取り消す。マウス用修飾キーだけ状態を維持する
        pointerKeys.values.distinct().filterNot { isMouseModifier(it) }.forEach { key(it, false) }
        val mousePointerKeys = pointerKeys.filterValues { isMouseModifier(it) }
        pointerKeys.clear()
        pointerKeys.putAll(mousePointerKeys)
        pressedUsages.retainAll(mouseModifierUsages)
        // 修飾キーが先に押されていた場合も、キーボード入力としては解除する
        pressedUsages.forEach { key(it, false) }
        mouseLastX = mouseCandidateX
        mouseLastY = mouseCandidateY
        updateMouseButtons()
        invalidate()
    }

    private fun updateMouseButtons() {
        if (!mouseMode) return
        val next = when {
            0xE3 in pressedUsages -> 0
            0x8B in pressedUsages -> 2
            0xE2 in pressedUsages || 0xE6 in pressedUsages -> 1
            else -> 0
        }
        if (next != mouseButtons) {
            mouseButtons = next
            mouse(next, 0, 0, 0)
        }
    }

    private fun finishMouseCandidate(pointerId: Int, x: Float, y: Float, cancelled: Boolean) {
        if (pointerId != mouseCandidateId) return
        handler.removeCallbacks(mouseLongPress)
        if (!mouseMode && !cancelled) {
            if (mouseCandidatePreviewUsage != 0) {
                pressedUsages.remove(mouseCandidatePreviewUsage)
                tapKey(mouseCandidatePreviewUsage)
            } else {
                addPointerKey(pointerId, mouseCandidateX, mouseCandidateY)
                removePointerKey(pointerId)
            }
        } else if (cancelled && mouseCandidatePreviewUsage != 0) {
            pressedUsages.remove(mouseCandidatePreviewUsage)
        }
        val wasMouseMode = mouseMode
        // 修飾キー上から始めたドラッグでは、その指を離した時点で修飾キーも解除する。
        if (wasMouseMode && mouseCandidatePreviewUsage != 0 && isMouseModifier(mouseCandidatePreviewUsage)) {
            removePointerKey(pointerId)
        }
        if (wasMouseMode && mouseButtons != 0) {
            mouse(0, 0, 0, 0)
            mouseButtons = 0
        }
        mouseCandidateId = -1
        mouseCandidatePreviewUsage = 0
        if (wasMouseMode && !cancelled) {
            // ジェスチャー終了後500msはAlt/無変換によるクリックを受け付ける
            handler.removeCallbacks(mouseGraceRunnable)
            handler.postDelayed(mouseGraceRunnable, 500L)
        } else {
            handler.removeCallbacks(mouseGraceRunnable)
            mouseMode = false
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.getX(0)
                val y = event.getY(0)
                if (x >= width * 2f / 3f) {
                    mouseCandidateId = event.getPointerId(0)
                    mouseCandidateX = x
                    mouseCandidateY = y
                    mouseCandidatePreviewUsage = hitBoxes.firstOrNull { it.rect.contains(x, y) }?.cap?.usage ?: 0
                    if (mouseCandidatePreviewUsage != 0) pressedUsages.add(mouseCandidatePreviewUsage)
                    handler.removeCallbacks(mouseGraceRunnable)
                    mouseLastX = x
                    mouseLastY = y
                    if (!mouseMode) mouseButtons = 0
                    handler.postDelayed(mouseLongPress, 350L)
                } else {
                    addPointerKey(event.getPointerId(0), x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pointerId = event.getPointerId(actionIndex)
                val usage = hitBoxes.firstOrNull { it.rect.contains(x, y) }?.cap?.usage
                if (mouseCandidateId == -1 && x >= width * 2f / 3f) {
                    // 先にAlt/無変換を押している場合でも、2本目の指でマウス操作を開始できる
                    mouseCandidateId = pointerId
                    mouseCandidateX = x
                    mouseCandidateY = y
                    mouseCandidatePreviewUsage = hitBoxes.firstOrNull { it.rect.contains(x, y) }?.cap?.usage ?: 0
                    if (mouseCandidatePreviewUsage != 0) pressedUsages.add(mouseCandidatePreviewUsage)
                    handler.removeCallbacks(mouseGraceRunnable)
                    mouseLastX = x
                    mouseLastY = y
                    handler.postDelayed(mouseLongPress, 350L)
                } else if (mouseCandidateId != -1 && !mouseMode) {
                    // マウスジェスチャー確定前でも、クリック用修飾キーは受け付ける
                    if (usage != null && isMouseModifier(usage)) addPointerKey(pointerId, x, y)
                } else {
                    addPointerKey(pointerId, x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mouseCandidateId != -1) {
                    val index = event.findPointerIndex(mouseCandidateId)
                    if (index >= 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        val distance = kotlin.math.hypot(
                            (x - mouseCandidateX).toDouble(),
                            (y - mouseCandidateY).toDouble()
                        )
                        if (!mouseMode && distance >= mouseThreshold) activateMouseMode()
                        if (mouseMode) {
                            val dx = ((x - mouseLastX) * mouseSensitivity).toInt()
                            val dy = ((y - mouseLastY) * mouseSensitivity).toInt()
                            if (0xE3 in pressedUsages) {
                                // スクロール速度を従来の1/8にし、小さな移動量も累積する
                                scrollRemainder += -dy / 64f
                                val wheel = scrollRemainder.toInt()
                                if (wheel != 0) {
                                    mouse(0, 0, 0, wheel)
                                    scrollRemainder -= wheel
                                }
                            } else if (dx != 0 || dy != 0) {
                                // クリックボタンの状態を維持したまま移動量も送信する
                                mouse(mouseButtons, dx, dy, 0)
                            }
                        }
                        mouseLastX = x
                        mouseLastY = y
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == mouseCandidateId) {
                    finishMouseCandidate(pointerId, event.getX(actionIndex), event.getY(actionIndex), false)
                } else {
                    removePointerKey(pointerId)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val pointerId = event.getPointerId(0)
                if (mouseCandidateId != -1) {
                    finishMouseCandidate(mouseCandidateId, event.x, event.y, cancelled)
                }
                if (!cancelled && event.y < 44f && event.x < 120f) settings()
                pointerKeys.keys.toList().forEach { removePointerKey(it) }
                if (mouseButtons != 0) mouse(0, 0, 0, 0)
                mouseButtons = 0
                if (cancelled) {
                    handler.removeCallbacks(mouseGraceRunnable)
                    mouseMode = false
                } else if (mouseCandidateId == -1 && !mouseMode) {
                    handler.removeCallbacks(mouseGraceRunnable)
                }
                mouseCandidateId = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun padButtonAt(x: Float, y: Float): Int {
        if (!hasPad() || y < height - 90f) return 0
        val relative = ((x - padStart()) / (padWidth() / 3f)).toInt()
        return (relative + 1).coerceIn(1, 3)
    }
}
