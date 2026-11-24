package jp.example.mykeyboard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object MozcRuntimeManager {
    private const val RELEASE_BASE = "https://github.com/mo64hysy/Android-APKs/releases/latest/download/"
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false

    fun isInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, "mozc-runtime")
        return File(dir, "libmozc.so").isFile && File(dir, "mozc.data").isFile
    }

    fun download(context: Context, onState: (String) -> Unit = {}) {
        val app = context.applicationContext
        if (isInstalled(app)) {
            mainHandler.post { onState("Mozcの準備ができています") }
            return
        }
        synchronized(this) {
            if (downloading) {
                mainHandler.post { onState("Mozcをダウンロード中です") }
                return
            }
            downloading = true
        }
        Thread {
            try {
                mainHandler.post { onState("Mozcの辞書・変換エンジンをダウンロードしています…") }
                val archive = File(app.cacheDir, "mozc-engine.zip")
                val checksum = File(app.cacheDir, "mozc-engine.zip.sha256")
                downloadFile("mozc-engine.zip", archive)
                downloadFile("mozc-engine.zip.sha256", checksum)
                val expected = checksum.readText().trim().substringBefore(" ").lowercase()
                val digest = MessageDigest.getInstance("SHA-256")
                archive.inputStream().buffered().use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(expected.matches(Regex("[0-9a-f]{64}")) && expected == actual) {
                    "ダウンロードしたMozcファイルの検証に失敗しました"
                }
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
                    ?: error("この端末のCPU形式には対応していません")
                val staging = File(app.filesDir, "mozc-runtime.tmp").apply {
                    deleteRecursively()
                    mkdirs()
                }
                var libraryFound = false
                var dataFound = false
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) when (entry.name) {
                            "lib/$abi/libmozc.so" -> {
                                File(staging, "libmozc.so").outputStream().use { zip.copyTo(it) }
                                libraryFound = true
                            }
                            "mozc.data" -> {
                                File(staging, "mozc.data").outputStream().use { zip.copyTo(it) }
                                dataFound = true
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(libraryFound && dataFound) { "Mozcファイルが不足しています（$abi）" }
                val target = File(app.filesDir, "mozc-runtime")
                target.deleteRecursively()
                check(staging.renameTo(target)) { "Mozcファイルを保存できませんでした" }
                archive.delete()
                checksum.delete()
                mainHandler.post { onState("Mozcの準備ができました") }
            } catch (e: Exception) {
                mainHandler.post { onState("ダウンロードに失敗しました：${e.message ?: "通信を確認してください"}") }
            } finally {
                synchronized(this) { downloading = false }
            }
        }.start()
    }

    private fun downloadFile(name: String, destination: File) {
        val connection = (URL(RELEASE_BASE + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "配布ファイルを取得できません（HTTP ${connection.responseCode}）"
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally { connection.disconnect() }
    }

    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}
