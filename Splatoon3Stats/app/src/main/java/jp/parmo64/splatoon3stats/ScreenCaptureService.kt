package jp.parmo64.splatoon3stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var captureStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_NOW) {
            if (!captureStarted) {
                captureStarted = true
                handler?.postDelayed({ captureAndRecognize() }, 350)
            }
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultData == null) { finishWithResult(""); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            worker = HandlerThread("screen-ocr").also { it.start() }
            handler = Handler(worker!!.looper)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { releaseCapture() }
            }, handler)
            virtualDisplay = projection?.createVirtualDisplay("Splatoon3StatsCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        } catch (_: Exception) { finishWithResult("") }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE_NOW)
        val pending = PendingIntent.getService(this, 42, captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Splatoon3Stats：キャプチャ準備完了")
            .setContentText("公式アプリを表示して「今の画面を読み取る」を押してください")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "今の画面を読み取る", pending).build())
            .build()
    }

    private fun captureAndRecognize() {
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) { finishWithResult(""); return }
        try {
            val plane = image.planes[0]
            val paddedBitmap = Bitmap.createBitmap(image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
                image.height, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            image.close()
            val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.textBlocks.joinToString("\n") { block -> block.lines.joinToString("\n") { it.text } }
                    finishWithResult(text)
                    recognizer.close()
                    bitmap.recycle()
                }
                .addOnFailureListener {
                    finishWithResult("")
                    recognizer.close()
                    bitmap.recycle()
                }
        } catch (_: Exception) {
            try { image.close() } catch (_: Exception) {}
            finishWithResult("")
        }
    }

    private fun finishWithResult(text: String) {
        sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker?.quitSafely()
        worker = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "画面読み取り", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { releaseCapture(); super.onDestroy() }

    companion object {
        const val ACTION_OCR_RESULT = "jp.parmo64.splatoon3stats.OCR_RESULT"
        const val ACTION_CAPTURE_NOW = "jp.parmo64.splatoon3stats.CAPTURE_NOW"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 41
    }
}
