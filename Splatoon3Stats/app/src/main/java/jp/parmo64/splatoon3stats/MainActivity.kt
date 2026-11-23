package jp.parmo64.splatoon3stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var extractedText = ""

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            status.text = "準備完了。Nintendo Switch Onlineに戻り、通知の「今の画面を読み取る」を押してください。"
        } else status.text = "画面キャプチャはキャンセルされました"
    }

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            extractedText = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty().trim()
            output.text = if (extractedText.isBlank()) "文字を読み取れませんでした。イカリング3のバトル履歴画面を表示して、もう一度お試しください。" else extractedText
            status.text = if (extractedText.isBlank()) "OCR結果なし" else "読み取り完了: ${extractedText.length}文字"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 16) }
        root.addView(TextView(this).apply { text = "Splatoon3Stats"; textSize = 22f; setPadding(0, 0, 0, 8) }, matchWrap())
        status = TextView(this).apply {
            text = "Nintendo Switch Onlineアプリでイカリング3を開き、読み取りたい画面を表示してください。"
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        root.addView(status, matchWrap())
        root.addView(Button(this).apply {
            text = "画面キャプチャを準備する"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                captureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "手順: ①このボタンで画面共有を許可 ②Nintendo Switch Onlineアプリに切り替える ③通知パネルを開き「今の画面を読み取る」を押す"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }, matchWrap())
        root.addView(TextView(this).apply { text = "OCRで読み取ったテキスト"; textSize = 15f; setPadding(0, 12, 0, 6) }, matchWrap())
        val scroll = ScrollView(this)
        output = TextView(this).apply { text = "読み取った内容がここに表示されます。"; textSize = 14f; setTextIsSelectable(true); setPadding(10, 10, 10, 10) }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { text = "GPT分析用テキストを共有"; setOnClickListener { shareText() } }, matchWrap())
        root.addView(Button(this).apply {
            text = "読み取り結果をコピー"
            setOnClickListener {
                if (extractedText.isNotBlank()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("イカリング3 OCR", extractedText))
                    status.text = "クリップボードにコピーしました"
                } else status.text = "先に画面を読み取ってください"
            }
        }, matchWrap())
        setContentView(root)
    }

    private fun shareText() {
        if (extractedText.isBlank()) { status.text = "先に公式アプリの画面を読み取ってください"; return }
        val prompt = """
            以下はNintendo Switch Onlineアプリのイカリング3画面をOCRで読み取ったテキストです。
            戦績情報を整理して分析してください。誤認識や判別できない箇所は不明とし、数値を推測で補わないでください。

            $extractedText
        """.trimIndent()
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, prompt) }
        startActivity(Intent.createChooser(send, "ChatGPTなどに共有"))
    }

    override fun onDestroy() {
        try { unregisterReceiver(ocrReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
