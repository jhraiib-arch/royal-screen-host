package com.royal.screenhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import java.io.ByteArrayOutputStream

class HostActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var webSocket: WebSocket? = null
    private lateinit var statusText: TextView

    private val roomCode = "854947"
    private var lastSentTime = 0L

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                mediaProjection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
                updateStatus("Screen capture started. Room: $roomCode")
                startCaptureLoop(width = 540, height = 960, dpi = 160)
            } else {
                updateStatus("Screen capture permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            text = "Starting Royal Screen Host..."
            textSize = 18f
            setPadding(40, 80, 40, 40)
        }
        setContentView(statusText)

        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        startForegroundService(serviceIntent)

        connectWebSocket()
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            statusText.text = msg
        }
    }

    private fun connectWebSocket() {
        updateStatus("Connecting to relay server...")
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://royal-relay-server.onrender.com")
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                ws.send("""{"type":"register_host","code":"$roomCode"}""")
                updateStatus("Connected. Requesting screen permission...")
                runOnUiThread {
                    projectionManager =
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                updateStatus("WebSocket error: ${t.message ?: "Connection failed"}")
            }
        })
    }

    private fun startCaptureLoop(width: Int, height: Int, dpi: Int) {
        val thread = HandlerThread("RoyalCaptureThread").apply { start() }
        val handler = Handler(thread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        mediaProjection?.createVirtualDisplay(
            "RoyalVirtualDisplay",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            if (now - lastSentTime < 200) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastSentTime = now

            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val rawBitmap = Bitmap.createBitmap(
                    width + rowPadding / plane.pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(plane.buffer)
                image.close()

                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                rawBitmap.recycle()

                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 40, out)
                cropped.recycle()

                val base64Frame = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                webSocket?.send("""{"type":"screen_frame","code":"$roomCode","img":"$base64Frame"}""")
            } catch (_: Exception) {
                image.close()
            }
        }, handler)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        webSocket?.close(1000, "Closed")
    }
}
