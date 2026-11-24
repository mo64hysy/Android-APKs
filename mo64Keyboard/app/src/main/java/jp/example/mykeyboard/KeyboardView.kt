
package jp.example.mykeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class KeyboardView(context: Context) : View(context) {

    enum class Mode { FLICK, QWERTY }
    enum class Gesture { TAP, LEFT, RIGHT, UP, DOWN }

    interface Listener {
        fun onKey(label: String, gesture: Gesture, longPress: Boolean)
        fun onCandidateSelected(candidate: String) {}
    }

    var listener: Listener? = null
    var mode = Mode.FLICK
    var shiftEnabled = false

    var candidates: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    private val candidateHeight = 42f * density
    private val settings by lazy {
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
    }
    private var navigationInset = 0

    private var downX = 0f
    private var downY = 0f
    private var activeKey = ""
    private var activeGesture = Gesture.TAP
    private var longFired = false

    private var previewGesture: Gesture? = null
    private var previewValue: String? = null

    private val keyRects = mutableListOf<Pair<String, RectF>>()
    private val candidateRects = mutableListOf<Pair<String, RectF>>()
    private var candidateScrollX = 0f
    private var candidateContentWidth = 0f
    private var candidateTouch = false
    private var candidateMoved = false
    private var candidateLastX = 0f

    // Backspaceは連続削除。左フリック後の長押しは全削除。
    // カーソルキーは長押しで連続移動し、端へのフリックは一度だけ実行する。
    private var backLeftFlick = false
    private val longRunnable = object : Runnable {
        override fun run() {
            if (activeKey.isEmpty()) return
            if (activeKey == "BACK") {
                if (backLeftFlick) {
                    if (!longFired) {
                        longFired = true
                        listener?.onKey("BACK", Gesture.LEFT, true)
                        invalidate()
                    }
                    return
                }
                longFired = true
                listener?.onKey("BACK", Gesture.TAP, true)
                invalidate()
                handler.postDelayed(this, 70L)
                return
            }
            if (activeKey == "LEFT" || activeKey == "RIGHT") {
                longFired = true
                listener?.onKey(activeKey, activeGesture, true)
                invalidate()
                val toEdge = (activeKey == "LEFT" && activeGesture == Gesture.LEFT) ||
                    (activeKey == "RIGHT" && activeGesture == Gesture.RIGHT)
                if (!toEdge) handler.postDelayed(this, 70L)
                return
            }
            longFired = true
            previewValue = getPreviewValue(activeKey, activeGesture, true)
            invalidate()
        }
    }

    private val flickRows = listOf(
        listOf("PASTE", "あ", "か", "さ", "BACK"),
        listOf("LEFT", "た", "な", "は", "RIGHT"),
        listOf("UNDO", "ま", "や", "ら", "SPACE"),
        listOf("MODE", "DAKUTEN", "わ", "COMMA", "ENTER")
    )

    private val qRows = listOf(
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() },
        listOf("SHIFT") + "zxcvbnm".map { it.toString() } + listOf("BACK"),
        listOf("MODE", "UNDO", "COMMA", "SPACE", "PERIOD", "ENTER")
    )

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val systemInset = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(
                WindowInsets.Type.navigationBars()
            ).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        navigationInset = (systemInset * 1.5f).toInt()

        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bg = if (dark) 0xFF17191D.toInt() else 0xFFE4E7EC.toInt()
        val keyColor = if (dark) 0xFF30343B.toInt() else 0xFFFFFFFF.toInt()
        val ink = if (dark) 0xFFF4F5F7.toInt() else 0xFF20242A.toInt()

        canvas.drawColor(bg)

        keyRects.clear()
        candidateRects.clear()

        val usableBottom = height - navigationInset
        if (usableBottom <= 0) return

        // 変換候補エリア
        paint.color = if (dark) 0xFF242830.toInt() else 0xFFD4D9E1.toInt()
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            candidateHeight,
            paint
        )

        if (candidates.isEmpty()) {
            candidateContentWidth = 0f
            candidateScrollX = 0f
            paint.color = ink
            paint.textSize = 12f * density
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT

            canvas.drawText(
                "変換候補（Mozc未接続）",
                width / 2f,
                candidateHeight / 2f -
                    (paint.ascent() + paint.descent()) / 2f,
                paint
            )
        } else {
            paint.textSize = 15f * density
            paint.typeface = Typeface.DEFAULT
            val itemWidths = candidates.map { candidate ->
                max(44f * density, paint.measureText(candidate) + 24f * density)
            }
            candidateContentWidth = itemWidths.sum() + 6f * density * candidates.size
            candidateScrollX = candidateScrollX.coerceIn(
                0f,
                max(0f, candidateContentWidth - width)
            )

            val saveCount = canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), candidateHeight)
            canvas.translate(-candidateScrollX, 0f)

            var left = 0f
            candidates.forEachIndexed { index, candidate ->
                val itemWidth = itemWidths[index]
                val rect = RectF(
                    left + 3f * density,
                    3f * density,
                    left + itemWidth - 3f * density,
                    candidateHeight - 3f * density
                )
                candidateRects.add(
                    candidate to RectF(
                        rect.left - candidateScrollX,
                        rect.top,
                        rect.right - candidateScrollX,
                        rect.bottom
                    )
                )

                paint.color = keyColor
                canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)

                paint.color = ink
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 15f * density
                canvas.drawText(
                    candidate,
                    rect.centerX(),
                    rect.centerY() - (paint.ascent() + paint.descent()) / 2f,
                    paint
                )
                left += itemWidth + 6f * density
            }
            canvas.restoreToCount(saveCount)
        }

        val rows = if (mode == Mode.FLICK) flickRows else qRows
        val availableHeight = usableBottom - candidateHeight
        val rowHeight = availableHeight / rows.size

        rows.forEachIndexed { rowIndex, row ->
            val weights = if (mode == Mode.QWERTY && rowIndex == rows.lastIndex) {
                row.map { if (it == "SPACE") 2.2f else 1f }
            } else {
                List(row.size) { 1f }
            }
            val totalWeight = weights.sum()

            row.forEachIndexed { columnIndex, label ->
                val leftWeight = weights.take(columnIndex).sum()
                val rightWeight = leftWeight + weights[columnIndex]
                if (label.isNotEmpty()) {
                    val rect = RectF(
                        width * leftWeight / totalWeight + 3f * density,
                        candidateHeight + rowIndex * rowHeight + 3f * density,
                        width * rightWeight / totalWeight - 3f * density,
                        candidateHeight + (rowIndex + 1) * rowHeight - 3f * density
                    )

                    keyRects.add(label to rect)

                    val active = label == activeKey

                    paint.color = when {
                        active && longFired -> 0xFF7899D0.toInt()
                        active -> 0xFF9CB7E8.toInt()
                        else -> keyColor
                    }

                    canvas.drawRoundRect(
                        rect,
                        7f * density,
                        7f * density,
                        paint
                    )

                    val text = when (label) {
                        "PASTE" -> "ペースト"
                        "UNDO" -> "←"
                        "BACK" -> "⌫"
                        "LEFT" -> "◀"
                        "RIGHT" -> "▶"
                        "SPACE" -> "␣"
                        "COMMA" -> ","
                        "PERIOD" -> "."
                        "ENTER" -> ""
                        "DAKUTEN" -> "゛゜"
                        "COMMA" -> "、"
                        "MODE" -> "あａ"
                        "SHIFT" -> ""
                        else -> label
                    }

                    paint.color = ink
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Typeface.DEFAULT_BOLD
                    paint.textSize = when (label) {
                        "PASTE", "DAKUTEN", "MODE", "SPACE" ->
                            12f * density
                        "ENTER", "SHIFT" -> 19f * density
                        else -> 19f * density
                    }

                    val centerY = rect.centerY() -
                        (paint.ascent() + paint.descent()) / 2f

                    if (label == "ENTER" || label == "SHIFT") {
                        drawVectorIcon(canvas, label, rect, ink)
                    } else {
                        canvas.drawText(
                            text,
                            rect.centerX(),
                            if (active && previewValue != null) centerY - 5f * density else centerY,
                            paint
                        )
                    }

                    // COMMAキーで入力できるフリック文字を常時表示
                    if (label == "COMMA") {
                        paint.color = ink
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = 8f * density
                        canvas.drawText(
                            "。 ？ ！ …",
                            rect.centerX(),
                            rect.bottom - 6f * density,
                            paint
                        )
                    }

                    // フリック方向に入力される数字・記号をキー上に表示
                    if (mode == Mode.QWERTY &&
                        label.length == 1 &&
                        label[0].isLetter()
                    ) {
                        paint.color = ink
                        paint.textSize = 10f * density
                        paint.typeface = Typeface.DEFAULT
                        val leftHint = qwertyLeftHint(label)
                        val upHint = qwertyDirectionalHint(label, Gesture.UP)
                        if (leftHint.isNotEmpty()) {
                            paint.textAlign = Paint.Align.LEFT
                            canvas.drawText(leftHint, rect.left + 6f * density,
                                rect.top + 14f * density, paint)
                        }
                        if (upHint.isNotEmpty()) {
                            paint.textAlign = Paint.Align.RIGHT
                            canvas.drawText(upHint, rect.right - 6f * density,
                                rect.top + 14f * density, paint)
                        }
                    }

                    // フリックプレビュー
                    if (active &&
                        previewGesture != null &&
                        previewValue != null
                    ) {
                        val arrow = when (previewGesture) {
                            Gesture.TAP -> "●"
                            Gesture.LEFT -> "←"
                            Gesture.RIGHT -> "→"
                            Gesture.UP -> "↑"
                            Gesture.DOWN -> "↓"
                            null -> ""
                        }

                        val badgeText = "$arrow $previewValue"

                        paint.textSize = 12f * density
                        paint.typeface = Typeface.DEFAULT_BOLD

                        val badgeWidth = min(
                            rect.width() - 8f * density,
                            max(
                                40f * density,
                                paint.measureText(badgeText) +
                                    14f * density
                            )
                        )

                        val badge = RectF(
                            rect.centerX() - badgeWidth / 2f,
                            rect.bottom - 26f * density,
                            rect.centerX() + badgeWidth / 2f,
                            rect.bottom - 4f * density
                        )

                        paint.color = 0xFF426DAA.toInt()
                        canvas.drawRoundRect(
                            badge,
                            6f * density,
                            6f * density,
                            paint
                        )

                        paint.color = 0xFFFFFFFF.toInt()
                        paint.textAlign = Paint.Align.CENTER

                        canvas.drawText(
                            badgeText,
                            badge.centerX(),
                            badge.centerY() -
                                (paint.ascent() + paint.descent()) / 2f,
                            paint
                        )
                    }

                }
            }
        }

        // ナビゲーション領域
        paint.color = bg
        canvas.drawRect(
            0f,
            usableBottom.toFloat(),
            width.toFloat(),
            height.toFloat(),
            paint
        )

        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawVectorIcon(canvas: Canvas, label: String, rect: RectF, color: Int) {
        if (label == "ENTER" || label == "UNDO") {
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = min(rect.width(), rect.height()) * 0.48f
            canvas.drawText(
                if (label == "ENTER") "➤" else "←",
                rect.centerX(),
                rect.centerY() - (paint.ascent() + paint.descent()) / 2f,
                paint
            )
            return
        }
        val size = min(rect.width(), rect.height()) * 0.42f
        val left = rect.centerX() - size / 2f
        val top = rect.centerY() - size / 2f
        paint.color = color
        paint.style = if (shiftEnabled) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        val path = android.graphics.Path().apply {
            moveTo(rect.centerX(), top)
            lineTo(left, rect.centerY() + size * 0.15f)
            lineTo(left + size * 0.22f, rect.centerY() + size * 0.15f)
            lineTo(left + size * 0.22f, rect.centerY() + size / 2f)
            lineTo(left + size * 0.78f, rect.centerY() + size / 2f)
            lineTo(left + size * 0.78f, rect.centerY() + size * 0.15f)
            lineTo(left + size, rect.centerY() + size * 0.15f)
            close()
        }
        if (shiftEnabled) canvas.drawPath(path, paint) else canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                candidateTouch = downY < candidateHeight
                candidateMoved = false
                candidateLastX = event.x

                longFired = false
                activeGesture = Gesture.TAP
                backLeftFlick = false
                previewGesture = null
                previewValue = null

                activeKey = findKey(event.x, event.y)

                if (downY >= candidateHeight && activeKey.isNotEmpty()) {
                    val delay = settings.getInt(
                        MainActivity.KEY_LONG_PRESS_MS,
                        MainActivity.DEFAULT_LONG_PRESS_MS
                    ).toLong()
                    handler.postDelayed(longRunnable, delay)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (candidateTouch) {
                    val delta = event.x - candidateLastX
                    if (abs(event.x - downX) > 8f * density) candidateMoved = true
                    candidateScrollX = (candidateScrollX - delta).coerceIn(
                        0f,
                        max(0f, candidateContentWidth - width)
                    )
                    candidateLastX = event.x
                    invalidate()
                    return true
                }

                val dx = event.x - downX
                val dy = event.y - downY

                if (activeKey == "BACK" &&
                    !backLeftFlick &&
                    dx <= -settings.getInt(
                        MainActivity.KEY_FLICK_THRESHOLD_DP,
                        MainActivity.DEFAULT_FLICK_THRESHOLD_DP
                    ) * density &&
                    abs(dx) > abs(dy)
                ) {
                    backLeftFlick = true
                    handler.removeCallbacks(longRunnable)
                    if (!longFired) {
                        handler.postDelayed(
                            longRunnable,
                            settings.getInt(
                                MainActivity.KEY_LONG_PRESS_MS,
                                MainActivity.DEFAULT_LONG_PRESS_MS
                            ).toLong()
                        )
                    }
                }

                if (activeKey.isNotEmpty()) {
                    val gesture = detectGesture(dx, dy)
                    activeGesture = gesture
                    previewGesture = gesture
                    previewValue = getPreviewValue(
                        activeKey,
                        gesture,
                        longFired
                    )
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)

                if (candidateTouch || downY < candidateHeight) {
                    if (!candidateMoved) {
                        val candidate = candidateRects.firstOrNull {
                            it.second.contains(event.x, event.y)
                        }?.first
                        if (candidate != null) listener?.onCandidateSelected(candidate)
                    }
                    candidateTouch = false
                    resetTouch()
                    return true
                }

                val dx = event.x - downX
                val dy = event.y - downY
                val gesture = detectGesture(dx, dy)

                if (activeKey.isNotEmpty()) {
                    val cursorLongAlreadyHandled =
                        (activeKey == "LEFT" || activeKey == "RIGHT") && longFired
                    if (!(activeKey == "BACK" && longFired) && !cursorLongAlreadyHandled) {
                        listener?.onKey(
                            activeKey,
                            gesture,
                            longFired
                        )
                    }
                }

                resetTouch()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                candidateTouch = false
                resetTouch()
                return true
            }
        }

        return true
    }

    private fun detectGesture(
        dx: Float,
        dy: Float
    ): Gesture {
        val threshold = settings.getInt(
            MainActivity.KEY_FLICK_THRESHOLD_DP,
            MainActivity.DEFAULT_FLICK_THRESHOLD_DP
        ).coerceIn(8, 60) * density
        if (abs(dx) < threshold && abs(dy) < threshold) {
            return Gesture.TAP
        }

        return if (abs(dx) > abs(dy)) {
            if (dx < 0) Gesture.LEFT else Gesture.RIGHT
        } else {
            if (dy < 0) Gesture.UP else Gesture.DOWN
        }
    }

    private fun getPreviewValue(
        key: String,
        gesture: Gesture,
        isLongPress: Boolean
    ): String? {
        if (key == "BACK" && gesture == Gesture.LEFT) {
            return if (isLongPress) "全削除" else "1行削除"
        }

        return if (isLongPress) {
            if (mode == Mode.FLICK) {
                longPressValue(key, gesture)
            } else {
                qwertyLongValue(key, gesture)
            }
        } else if (mode == Mode.FLICK) {
            flickValue(key, gesture)
        } else {
            qwertyValue(key, gesture)
        }
    }

    private fun resetTouch() {
        handler.removeCallbacks(longRunnable)
        activeKey = ""
        activeGesture = Gesture.TAP
        longFired = false
        backLeftFlick = false
        previewGesture = null
        previewValue = null
        invalidate()
    }

    private fun findKey(x: Float, y: Float): String {
        return keyRects.firstOrNull {
            it.second.contains(x, y)
        }?.first ?: ""
    }

    companion object {

        fun flickValue(
            key: String,
            g: Gesture
        ): String? {
            if (key == "COMMA") {
                return when (g) {
                    Gesture.TAP -> "、"
                    Gesture.LEFT -> "。"
                    Gesture.UP -> "？"
                    Gesture.RIGHT -> "！"
                    Gesture.DOWN -> "…"
                }
            }

            if (key == "や") {
                return when (g) {
                    Gesture.LEFT -> "（"
                    Gesture.RIGHT -> "）"
                    Gesture.TAP -> "や"
                    Gesture.UP -> "ゆ"
                    Gesture.DOWN -> "よ"
                }
            }

            if (key.length != 1) {
                return when (key) {
                    "SPACE", "BACK", "LEFT", "RIGHT",
                    "ENTER", "DAKUTEN", "PASTE", "MODE", "UNDO" -> key
                    else -> null
                }
            }

            val row = when (key) {
                "あ" -> listOf("あ", "い", "う", "え", "お")
                "か" -> listOf("か", "き", "く", "け", "こ")
                "さ" -> listOf("さ", "し", "す", "せ", "そ")
                "た" -> listOf("た", "ち", "つ", "て", "と")
                "な" -> listOf("な", "に", "ぬ", "ね", "の")
                "は" -> listOf("は", "ひ", "ふ", "へ", "ほ")
                "ま" -> listOf("ま", "み", "む", "め", "も")
                "や" -> listOf("や", "ゃ", "ゆ", "ゅ", "よ")
                "ら" -> listOf("ら", "り", "る", "れ", "ろ")
                "わ" -> listOf("わ", "を", "ん", "ー", "〜")
                else -> return key
            }

            return row[when (g) {
                Gesture.TAP -> 0
                Gesture.LEFT -> 1
                Gesture.UP -> 2
                Gesture.RIGHT -> 3
                Gesture.DOWN -> 4
            }]
        }

        fun longPressValue(
            key: String,
            g: Gesture
        ): String? {
            val digits = mapOf(
                "あ" to "1", "か" to "2", "さ" to "3",
                "た" to "4", "な" to "5", "は" to "6",
                "ま" to "7", "や" to "8", "ら" to "9",
                "わ" to "0"
            )

            if (g == Gesture.TAP) return digits[key]

            val symbols = when (key) {
                "あ", "か", "さ" -> listOf("!", "@", "#", "$")
                "た", "な", "は" -> listOf("%", "&", "*", "(")
                "ま", "や", "ら" -> listOf(")", "-", "_", "+")
                "わ" -> listOf("=", "/", "?", ":")
                else -> listOf("!", "?", ".", ",")
            }

            return symbols[when (g) {
                Gesture.LEFT -> 0
                Gesture.UP -> 1
                Gesture.RIGHT -> 2
                Gesture.DOWN -> 3
                Gesture.TAP -> 0
            }]
        }

        fun qwertyValue(
            key: String,
            g: Gesture
        ): String? {
            when (key) {
                "SPACE", "BACK", "ENTER", "MODE", "UNDO" -> return key
                "COMMA" -> return if (g == Gesture.UP) "/" else ","
                "PERIOD" -> return "."
            }
            if (key.length != 1 || !key[0].isLetter()) return null
            val lower = key.lowercase()
            if (g == Gesture.LEFT) {
                return when (lower) {
                    "x" -> "~"
                    "c" -> "`"
                    "z" -> "^"
                    "k" -> "«"
                    "l" -> "»"
                    else -> lower
                }
            }
            val topRowSymbols = mapOf(
                "q" to "%", "w" to "\\", "e" to "|", "r" to "=",
                "t" to "[", "y" to "]", "u" to "<", "i" to ">",
                "o" to "{", "p" to "}"
            )
            if (lower in topRowSymbols) {
                return when (g) {
                    Gesture.UP -> qwertyDigit(lower)
                    Gesture.DOWN -> key.uppercase()
                    else -> lower
                }
            }
            return when (g) {
                Gesture.DOWN -> key.uppercase()
                Gesture.UP -> qwertySymbol(key)
                else -> lower
            }
        }

        fun qwertyHint(key: String): String = when (key.lowercase()) {
            else -> qwertySymbol(key)
        }

        fun qwertyLeftHint(key: String): String = when (key.lowercase()) {
            "q" -> "%"
            "w" -> "\\"
            "e" -> "|"
            "r" -> "="
            "t" -> "["
            "y" -> "]"
            "u" -> "<"
            "i" -> ">"
            "o" -> "{"
            "p" -> "}"
            "x" -> "~"
            "c" -> "`"
            "z" -> "^"
            "k" -> "«"
            "l" -> "»"
            else -> ""
        }

        fun qwertyDirectionalHint(key: String, gesture: Gesture): String {
            val lower = key.lowercase()
            return when (gesture) {
                Gesture.LEFT -> qwertyLeftHint(lower)
                Gesture.UP -> if (lower in setOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")) {
                    qwertyDigit(lower)
                } else {
                    qwertySymbol(lower).takeIf { it != lower } ?: ""
                }
                else -> ""
            }
        }

        fun qwertyDigit(key: String): String = when (key) {
            "q" -> "1"; "w" -> "2"; "e" -> "3"; "r" -> "4"; "t" -> "5"
            "y" -> "6"; "u" -> "7"; "i" -> "8"; "o" -> "9"; "p" -> "0"
            else -> key
        }

        fun qwertySymbol(key: String): String {
            val map = mapOf(
                'a' to '@', 's' to '#', 'd' to '¥',
                'f' to '-', 'g' to '&',
                'h' to '-', 'j' to '+', 'k' to '(', 'l' to ')',
                'z' to '*', 'x' to '"', 'c' to '\'',
                'v' to ':', 'b' to ';', 'n' to '!',
                'm' to '?'
            )
            return map[key.lowercase()[0]]?.toString() ?: key
        }

        fun qwertyLongValue(
            key: String,
            g: Gesture
        ): String? {
            return if (key.length == 1 && key[0].isLetter()) {
                if (key.lowercase() in setOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")) {
                    qwertyDigit(key.lowercase())
                } else {
                    qwertySymbol(key)
                }
            } else {
                null
            }
        }

    }
}
