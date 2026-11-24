package jp.example.mykeyboard.mozc

import android.content.Context
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand

/** Converts kana input into real Mozc candidates and editor text. */
class MozcSession(context: Context) {
    private val engine = MozcEngine.get(context)
    val isAvailable: Boolean get() = engine != null

    data class Candidate(val id: Int, val text: String)
    data class State(
        val committedText: String = "",
        val preedit: String = "",
        val candidates: List<Candidate> = emptyList(),
        val consumed: Boolean = false
    )

    fun sendText(text: String): State? {
        val key = KeyEvent.newBuilder()
        if (text.length == 1 && text[0].code in 0x20..0x7E) {
            key.keyCode = text[0].code
        } else {
            key.keyString = text
        }
        return engine?.sendKey(key.build())?.toState()
    }

    fun sendSpecialKey(key: KeyEvent.SpecialKey): State? {
        return engine?.sendKey(KeyEvent.newBuilder().setSpecialKey(key).build())?.toState()
    }

    fun submit(): State? = command(SessionCommand.CommandType.SUBMIT)
    fun revert(): State? = command(SessionCommand.CommandType.REVERT)
    fun reset(): State? = command(SessionCommand.CommandType.RESET_CONTEXT)

    fun setLearningEnabled(enabled: Boolean): Boolean =
        engine?.setLearningEnabled(enabled) ?: false

    fun addUserWord(reading: String, word: String): Boolean {
        if (reading.isBlank() || word.isBlank()) return false
        return engine?.importUserDictionary(reading.trim(), word.trim()) ?: false
    }

    fun selectCandidate(id: Int): State? {
        val command = SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.SUBMIT_CANDIDATE)
            .setId(id)
            .build()
        return engine?.sendCommand(command)?.toState()
    }

    private fun command(type: SessionCommand.CommandType): State? {
        val command = SessionCommand.newBuilder().setType(type).build()
        return engine?.sendCommand(command)?.toState()
    }

    private fun Output.toState(): State {
        val preeditText = if (hasPreedit()) {
            preedit.segmentList.joinToString(separator = "") { it.value }
        } else {
            ""
        }
        val candidateList = if (hasCandidateWindow()) {
            candidateWindow.candidateList.map { Candidate(it.id, it.value) }
        } else {
            emptyList()
        }
        return State(
            committedText = if (hasResult()) result.value else "",
            preedit = preeditText,
            candidates = candidateList,
            consumed = consumed
        )
    }
}
