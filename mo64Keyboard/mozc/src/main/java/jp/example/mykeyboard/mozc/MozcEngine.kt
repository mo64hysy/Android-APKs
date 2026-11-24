package jp.example.mykeyboard.mozc

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryImportData
import java.io.File

/** Mozc host. Its native library and dictionary are downloaded after installation. */
class MozcEngine private constructor(context: Context) {
    private val lock = Any()
    private var sessionId = 0L
    private var learningEnabled = context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
        .getBoolean("learning_enabled", true)

    init {
        val profile = File(context.filesDir, "mozc-profile").apply { mkdirs() }
        val data = File(context.filesDir, DATA_FILE)
        check(data.isFile) { "Mozc dictionary has not been downloaded" }
        check(MozcJNI.onPostLoad(profile.absolutePath, data.absolutePath)) {
            "Mozc native initialization failed"
        }
    }

    fun setLearningEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (!ensureSessionLocked()) return@synchronized false
        if (learningEnabled == enabled) return@synchronized true
        val output = evalLocked(Input.newBuilder().setType(Input.CommandType.SET_REQUEST)
            .setRequest(Request.newBuilder().setZeroQuerySuggestion(true).setMixedConversion(true)
                .setAutoPartialSuggestion(true).setCandidatePageSize(30).setCandidatesSizeLimit(100)
                .setIsIncognitoMode(!enabled).build())) ?: return@synchronized false
        if (output.errorCode != Output.ErrorCode.SESSION_SUCCESS) return@synchronized false
        learningEnabled = enabled
        true
    }

    fun sendKey(key: org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent): Output? =
        synchronized(lock) {
            if (!ensureSessionLocked()) return null
            evalLocked(Input.newBuilder().setType(Input.CommandType.SEND_KEY).setId(sessionId).setKey(key))
        }

    fun importUserDictionary(reading: String, word: String): Boolean = synchronized(lock) {
        val tsv = "$reading\t$word\t名詞\n"
        val input = Input.newBuilder().setType(Input.CommandType.IMPORT_USER_DICTIONARY)
            .setUserDictionaryImportData(UserDictionaryImportData.newBuilder()
                .setDictionaryName("mo64 Keyboard").setData(tsv).build())
        val output = evalLocked(input) ?: return false
        if (output.errorCode != Output.ErrorCode.SESSION_SUCCESS) return false
        evalLocked(Input.newBuilder().setType(Input.CommandType.RELOAD_AND_WAIT))
        true
    }

    fun sendCommand(command: SessionCommand): Output? = synchronized(lock) {
        if (!ensureSessionLocked()) return null
        evalLocked(Input.newBuilder().setType(Input.CommandType.SEND_COMMAND).setId(sessionId).setCommand(command))
    }

    private fun ensureSessionLocked(): Boolean {
        if (sessionId != 0L) return true
        val output = evalLocked(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION)) ?: return false
        if (!output.hasId()) return false
        sessionId = output.id
        evalLocked(Input.newBuilder().setType(Input.CommandType.SET_REQUEST)
            .setRequest(Request.newBuilder().setZeroQuerySuggestion(true).setMixedConversion(true)
                .setAutoPartialSuggestion(true).setCandidatePageSize(30).setCandidatesSizeLimit(100)
                .setIsIncognitoMode(!learningEnabled).build()))
        return true
    }

    private fun evalLocked(input: Input.Builder): Output? {
        return try {
            val bytes = Command.newBuilder().setInput(input).build().toByteArray()
            val response = MozcJNI.evalCommand(bytes) ?: return null
            Command.parseFrom(response).output
        } catch (e: Exception) {
            Log.e(TAG, "Mozc command failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "MozcEngine"
        private const val DATA_FILE = "mozc.data"
        @Volatile private var instance: MozcEngine? = null
        @Volatile private var loadFailed = false

        fun get(context: Context): MozcEngine? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (loadFailed) return null
                val runtime = File(context.applicationContext.filesDir, "mozc-runtime")
                val library = File(runtime, "libmozc.so")
                if (!library.isFile || !File(runtime, DATA_FILE).isFile) return null
                val created = try {
                    System.load(library.absolutePath)
                    check(MozcJNI.initialize()) { "Mozc JNI registration failed" }
                    MozcEngine(context.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start Mozc", e)
                    loadFailed = true
                    return null
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Mozc native library is unavailable", e)
                    loadFailed = true
                    return null
                }
                instance = created
                return created
            }
        }
    }
}
