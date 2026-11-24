package jp.example.mykeyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import jp.example.mykeyboard.mozc.MozcSession
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

class MyInputMethodService :
    InputMethodService(),
    KeyboardView.Listener {

    private var keyboard: KeyboardView? = null
    private var layoutMode = KeyboardView.Mode.FLICK
    private var composing = StringBuilder()
    private var shiftEnabled = false
    private var mozcSession: MozcSession? = null
    private var mozcCandidates: List<MozcSession.Candidate> = emptyList()
    private data class UndoSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)
    private val undoHistory = ArrayDeque<UndoSnapshot>()

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        if (mozcSession == null) {
            mozcSession = MozcSession(this).takeIf { it.isAvailable }
        }
        keyboard = KeyboardView(this).also {
            it.listener = this
            it.mode = layoutMode
            it.shiftEnabled = shiftEnabled
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx()
            )
        }

        return keyboard!!
    }


    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun keyboardHeightPx(): Int {
        val heightDp = getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_HEIGHT_DP, MainActivity.DEFAULT_HEIGHT_DP)
            .coerceIn(220, 440)
        return dp(heightDp)
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInput(attribute, restarting)

        if (!restarting) {
            composing.clear()
            mozcCandidates = emptyList()
            mozcSession?.reset()
            updateCandidates()
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)

        if (mozcSession == null) {
            mozcSession = MozcSession(this).takeIf { it.isAvailable }
        }
        keyboard?.mode = layoutMode
        keyboard?.layoutParams = keyboard?.layoutParams?.apply {
            height = keyboardHeightPx()
        }
        updateCandidates()
        val learningEnabled = getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_LEARNING_ENABLED, MainActivity.DEFAULT_LEARNING_ENABLED)
        mozcSession?.setLearningEnabled(learningEnabled)
    }

    override fun onKey(
        label: String,
        gesture: KeyboardView.Gesture,
        longPress: Boolean
    ) {
        val ic = currentInputConnection ?: return

        if (label == "MODE") {
            val nextMode =
                if (layoutMode == KeyboardView.Mode.FLICK) {
                    KeyboardView.Mode.QWERTY
                } else {
                    KeyboardView.Mode.FLICK
                }
            if (nextMode == KeyboardView.Mode.QWERTY) {
                finishComposition(ic)
                mozcCandidates = emptyList()
                updateCandidates()
            }
            layoutMode = nextMode
            keyboard?.mode = layoutMode
            keyboard?.invalidate()
            return
        }

        if (label == "SHIFT") {
            shiftEnabled = !shiftEnabled
            keyboard?.shiftEnabled = shiftEnabled
            keyboard?.invalidate()
            return
        }

        if (label == "UNDO") {
            undoInput(ic)
            return
        }

        if (label == "PASTE") {
            pasteText(ic)
            return
        }

        if (label != "LEFT" && label != "RIGHT" && label != "SPACE") {
            saveUndoSnapshot(ic)
        }

        if (label == "BACK") {
            when {
                longPress && gesture == KeyboardView.Gesture.LEFT -> deleteEntireEditor(ic)
                gesture == KeyboardView.Gesture.LEFT -> {
                    if (composing.isNotEmpty()) clearComposing(ic) else deleteCurrentLine(ic)
                }
                else -> deleteBackward(ic)
            }
            return
        }

        if (label == "SPACE") {
            if (layoutMode == KeyboardView.Mode.QWERTY) {
                if (gesture == KeyboardView.Gesture.TAP) {
                    finishComposition(ic)
                    ic.commitText(" ", 1)
                } else {
                    moveCursor(ic, gesture)
                }
            } else {
                requestConversion(ic)
            }
            return
        }

        // 未確定文字があれば確定だけを行い、次のEnterで改行する。
        // すでに確定済みなら、実際の改行文字を入力する。
        if (label == "ENTER") {
            if (composing.isNotEmpty()) {
                finishComposition(ic)
            } else {
                ic.commitText("\n", 1)
            }
            return
        }

        if (label == "LEFT" || label == "RIGHT") {
            when {
                label == "LEFT" && gesture == KeyboardView.Gesture.LEFT ->
                    moveCursorToEdge(ic, toEnd = false)
                label == "RIGHT" && gesture == KeyboardView.Gesture.RIGHT ->
                    moveCursorToEdge(ic, toEnd = true)
                else -> moveCursor(ic, gesture, label)
            }
            return
        }

        if (label == "DAKUTEN") {
            changeLastKana(ic)
            return
        }

        var output: String = (
            if (longPress) {
                if (layoutMode == KeyboardView.Mode.FLICK) {
                    KeyboardView.longPressValue(label, gesture)
                } else {
                    KeyboardView.qwertyLongValue(label, gesture)
                }
            } else if (layoutMode == KeyboardView.Mode.FLICK) {
                KeyboardView.flickValue(label, gesture)
            } else {
                KeyboardView.qwertyValue(label, gesture)
            }
        ) ?: return

        if (layoutMode == KeyboardView.Mode.QWERTY &&
            shiftEnabled &&
            output.length == 1 &&
            output[0].isLetter()
        ) {
            output = output.uppercase()
            shiftEnabled = false
            keyboard?.shiftEnabled = false
        }

        if (layoutMode == KeyboardView.Mode.QWERTY) {
            // QWERTYはローマ字変換を通さず、入力文字をそのまま確定する。
            finishComposition(ic)
            ic.commitText(output, 1)
        } else if (mozcSession != null && !longPress) {
            applyMozcState(ic, mozcSession?.sendText(output))
        } else if (!longPress) {
            composing.append(output)
            ic.setComposingText(composing.toString(), 1)
            updateCandidates()
        } else {
            finishComposition(ic)
            ic.commitText(output, 1)
        }
    }

    private fun saveUndoSnapshot(ic: InputConnection) {
        val extracted = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 100000
                hintMaxLines = 10000
            },
            0
        ) ?: return
        val text = extracted.text?.toString() ?: return
        val offset = extracted.startOffset.coerceAtLeast(0)
        if (offset != 0) return
        undoHistory.addLast(
            UndoSnapshot(
                text,
                extracted.selectionStart.coerceIn(0, text.length),
                extracted.selectionEnd.coerceIn(0, text.length)
            )
        )
        while (undoHistory.size > 30) undoHistory.removeFirst()
    }

    private fun undoInput(ic: InputConnection) {
        val snapshot = undoHistory.removeLastOrNull()
        if (snapshot == null) {
            ic.performContextMenuAction(android.R.id.undo)
            return
        }
        ic.finishComposingText()
        val current = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 100000
                hintMaxLines = 10000
            },
            0
        )?.text?.length ?: 0
        if (!ic.setSelection(0, current)) {
            undoHistory.addLast(snapshot)
            return
        }
        ic.commitText(snapshot.text, 1)
        ic.setSelection(snapshot.selectionStart, snapshot.selectionEnd)
        composing.clear()
        mozcCandidates = emptyList()
        mozcSession?.reset()
        updateCandidates()
    }

    private fun moveCursorToEdge(ic: InputConnection, toEnd: Boolean) {
        finishComposition(ic)
        val extracted = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(),
            0
        )
        if (extracted?.text != null) {
            val offset = extracted.partialStartOffset.coerceAtLeast(0)
            val target = if (toEnd) {
                extracted.text.length
            } else {
                0
            }
            val absolute = offset + target
            ic.setSelection(absolute, absolute)
        }
    }

    private fun moveCursor(
        ic: InputConnection,
        gesture: KeyboardView.Gesture,
        fallback: String? = null
    ) {
        finishComposition(ic)
        val extracted = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 100000
                hintMaxLines = 10000
            },
            0
        )
        val text = extracted?.text?.toString()
        if (text != null) {
            val offset = extracted.partialStartOffset.coerceAtLeast(0)
            val selectionStart = (extracted.selectionStart - offset)
                .coerceIn(0, text.length)
            val selectionEnd = (extracted.selectionEnd - offset)
                .coerceIn(0, text.length)
            val cursor = maxOf(selectionStart, selectionEnd)
            val target = when (gesture) {
                KeyboardView.Gesture.LEFT -> (minOf(selectionStart, selectionEnd) - 1).coerceAtLeast(0)
                KeyboardView.Gesture.RIGHT -> (maxOf(selectionStart, selectionEnd) + 1).coerceAtMost(text.length)
                KeyboardView.Gesture.UP,
                KeyboardView.Gesture.DOWN -> {
                    val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
                        .let { if (it < 0) 0 else it + 1 }
                    val column = cursor - lineStart
                    if (gesture == KeyboardView.Gesture.UP) {
                        if (lineStart == 0) cursor else {
                            val previousEnd = lineStart - 1
                            val previousStart = text.lastIndexOf('\n', (previousEnd - 1).coerceAtLeast(0))
                                .let { if (it < 0) 0 else it + 1 }
                            minOf(previousStart + column, previousEnd)
                        }
                    } else {
                        val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
                        if (lineEnd == text.length) cursor else {
                            val nextStart = lineEnd + 1
                            val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                            minOf(nextStart + column, nextEnd)
                        }
                    }
                }
                KeyboardView.Gesture.TAP -> if (fallback == "RIGHT") {
                    (maxOf(selectionStart, selectionEnd) + 1).coerceAtMost(text.length)
                } else {
                    (minOf(selectionStart, selectionEnd) - 1).coerceAtLeast(0)
                }
            }
            val absolute = offset + target
            ic.setSelection(absolute, absolute)
            return
        }

        // 抽出テキスト非対応の入力欄では、左右移動のみ選択範囲を使って処理する。
        if (gesture == KeyboardView.Gesture.LEFT || gesture == KeyboardView.Gesture.RIGHT) {
            val before = ic.getTextBeforeCursor(100000, 0)?.length ?: return
            val after = ic.getTextAfterCursor(100000, 0)?.length ?: return
            val current = before
            val target = if (gesture == KeyboardView.Gesture.LEFT) {
                (current - 1).coerceAtLeast(0)
            } else {
                (current + 1).coerceAtMost(current + after)
            }
            ic.setSelection(target, target)
        }
    }

    private fun clearComposing(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            if (mozcSession != null) {
                applyMozcState(ic, mozcSession?.revert())
            } else {
                composing.clear()
                ic.setComposingText("", 1)
                ic.finishComposingText()
                updateCandidates()
            }
        }
    }

    private fun deleteCurrentLine(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
        if (before.isEmpty() && after.isEmpty()) return

        val previousBreak = before.lastIndexOf('\n')
        val nextBreak = after.indexOf('\n')
        var beforeLength = before.length - previousBreak - 1
        val afterLength = if (nextBreak >= 0) nextBreak + 1 else after.length
        if (nextBreak < 0 && previousBreak >= 0) beforeLength += 1
        ic.deleteSurroundingText(beforeLength, afterLength)
    }

    private fun deleteEntireEditor(ic: InputConnection) {
        finishComposition(ic)
        val selected = ic.performContextMenuAction(android.R.id.selectAll)
        if (selected) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun deleteBackward(ic: InputConnection) {
        if (composing.isNotEmpty() && mozcSession != null) {
            applyMozcState(ic, mozcSession?.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE))
        } else if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            ic.setComposingText(composing.toString(), 1)
            if (composing.isEmpty()) {
                ic.finishComposingText()
            }
            updateCandidates()
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onCandidateSelected(candidate: String) {
        val ic = currentInputConnection ?: return
        val mozcCandidate = mozcCandidates.firstOrNull { it.text == candidate }
        if (mozcCandidate != null && mozcSession != null) {
            applyMozcState(ic, mozcSession?.selectCandidate(mozcCandidate.id))
        } else {
            ic.commitText(candidate, 1)
            composing.clear()
            updateCandidates()
        }
    }

    private fun updateCandidates() {
        keyboard?.candidates =
            if (mozcCandidates.isNotEmpty()) {
                mozcCandidates.map { it.text }
            } else if (composing.isNotEmpty()) {
                listOf(composing.toString())
            } else {
                emptyList()
            }
    }

    private fun applyMozcState(ic: InputConnection, state: MozcSession.State?) {
        state ?: return
        if (state.committedText.isNotEmpty()) {
            ic.commitText(state.committedText, 1)
        }
        composing.clear()
        composing.append(state.preedit)
        mozcCandidates = state.candidates
        if (state.preedit.isNotEmpty()) {
            ic.setComposingText(state.preedit, 1)
        } else {
            ic.setComposingText("", 1)
            ic.finishComposingText()
        }
        updateCandidates()
    }

    private fun requestConversion(ic: InputConnection) {
        if (mozcSession != null) {
            if (composing.isEmpty()) {
                ic.commitText(" ", 1)
            } else {
                applyMozcState(ic, mozcSession?.sendSpecialKey(KeyEvent.SpecialKey.SPACE))
            }
            return
        }

        if (composing.isEmpty()) {
            ic.commitText(" ", 1)
            return
        }

        finishComposition(ic)
        ic.commitText(" ", 1)
    }

    private fun pasteText(ic: InputConnection) {
        finishComposition(ic)

        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        if (!clipboard.hasPrimaryClip()) return

        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0)
            .coerceToText(this)
            ?.toString()
            ?: return

        ic.commitText(text, 1)
    }

    private fun changeLastKana(ic: InputConnection) {
        if (composing.isEmpty()) return

        val last = composing.last()

        val changed = when (last) {
            'つ' -> 'っ'
            'っ' -> 'づ'
            'づ' -> 'つ'
            'は' -> 'ば'
            'ひ' -> 'び'
            'ふ' -> 'ぶ'
            'へ' -> 'べ'
            'ほ' -> 'ぼ'
            'ば' -> 'ぱ'
            'び' -> 'ぴ'
            'ぶ' -> 'ぷ'
            'べ' -> 'ぺ'
            'ぼ' -> 'ぽ'
            'ぱ' -> 'は'
            'ぴ' -> 'ひ'
            'ぷ' -> 'ふ'
            'ぺ' -> 'へ'
            'ぽ' -> 'ほ'
            'か' -> 'が'
            'き' -> 'ぎ'
            'く' -> 'ぐ'
            'け' -> 'げ'
            'こ' -> 'ご'
            'が' -> 'か'
            'ぎ' -> 'き'
            'ぐ' -> 'く'
            'げ' -> 'け'
            'ご' -> 'こ'
            'さ' -> 'ざ'
            'し' -> 'じ'
            'す' -> 'ず'
            'せ' -> 'ぜ'
            'そ' -> 'ぞ'
            'ざ' -> 'さ'
            'じ' -> 'し'
            'ず' -> 'す'
            'ぜ' -> 'せ'
            'ぞ' -> 'そ'
            'た' -> 'だ'
            'ち' -> 'ぢ'
            'て' -> 'で'
            'と' -> 'ど'
            'だ' -> 'た'
            'ぢ' -> 'ち'
            'で' -> 'て'
            'ど' -> 'と'
            'う' -> 'ゔ'
            'ゔ' -> 'う'
            else -> toggleSmallKana(last)
        }

        if (mozcSession != null) {
            mozcSession?.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE)
            applyMozcState(ic, mozcSession?.sendText(changed.toString()))
        } else {
            composing.setCharAt(composing.length - 1, changed)
            ic.setComposingText(composing.toString(), 1)
            updateCandidates()
        }
    }

    private fun finishComposition(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            if (mozcSession != null) {
                applyMozcState(ic, mozcSession?.submit())
            } else {
                ic.commitText(composing.toString(), 1)
                composing.clear()
                updateCandidates()
            }
        }
    }

    private fun toggleSmallKana(c: Char): Char {
        return when (c) {
            'あ' -> 'ぁ'
            'い' -> 'ぃ'
            'う' -> 'ぅ'
            'え' -> 'ぇ'
            'お' -> 'ぉ'
            'や' -> 'ゃ'
            'ゆ' -> 'ゅ'
            'よ' -> 'ょ'
            'わ' -> 'ゎ'
            'ぁ' -> 'あ'
            'ぃ' -> 'い'
            'ぅ' -> 'う'
            'ぇ' -> 'え'
            'ぉ' -> 'お'
            'ゃ' -> 'や'
            'ゅ' -> 'ゆ'
            'ょ' -> 'よ'
            'ゎ' -> 'わ'
            else -> c
        }
    }

    override fun onFinishInput() {
        composing.clear()
        mozcCandidates = emptyList()
        mozcSession?.reset()
        updateCandidates()
        super.onFinishInput()
    }
}
