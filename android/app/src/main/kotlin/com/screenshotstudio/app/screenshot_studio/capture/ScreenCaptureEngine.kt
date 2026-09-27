package com.screenshotstudio.app.screenshot_studio.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class ScreenCaptureEngine(private val context: Context) {

    private val TAG = "SSCapture"
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    fun captureFrame(
        mediaProjection: MediaProjection,
        outputDir: File,
        callback: (Result<String>) -> Unit
    ) {
        // Android 14+ requires a MediaProjection.Callback to be registered
        // before createVirtualDisplay(), otherwise it throws a SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !callbackRegistered) {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {}, handler)
            callbackRegistered = true
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Setup ImageReader for maximum fidelity (RGBA_8888)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val display = try {
            mediaProjection.createVirtualDisplay(
                "ScreenshotStudio_VirtualDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            cleanUp()
            callback(Result.failure(RuntimeException("createVirtualDisplay failed: ${t.message}")))
            return
        }
        virtualDisplay = display
        Log.i(TAG, "virtualDisplay created=${display != null} ${width}x$height")

        if (display == null) {
            cleanUp()
            callback(Result.failure(RuntimeException("createVirtualDisplay returned null")))
            return
        }

        var captured = false

        val timeout = Runnable {
            if (!captured) {
                captured = true
                Log.e(TAG, "capture timed out waiting for first frame")
                cleanUp()
                callback(Result.failure(RuntimeException("Timed out waiting for screen frame")))
            }
        }
        handler.postDelayed(timeout, 5000)

        reader.setOnImageAvailableListener({ ir ->
            if (captured) return@setOnImageAvailableListener
            var image: Image? = null
            try {
                image = ir.acquireLatestImage()
                if (image != null) {
                    captured = true
                    handler.removeCallbacks(timeout)
                    val planes = image.planes
                    val buffer: ByteBuffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop out the padding if rowPadding exists
                    val finalBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }

                    // Save high-resolution PNG
                    val fileName = "capture_${System.currentTimeMillis()}.png"
                    val file = File(outputDir, fileName)
                    FileOutputStream(file).use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    Log.i(TAG, "frame captured -> ${file.absolutePath}")
                    cleanUp()
                    callback(Result.success(file.absolutePath))
                }
            } catch (e: Exception) {
                if (!captured) {
                    captured = true
                    handler.removeCallbacks(timeout)
                    Log.e(TAG, "frame capture error", e)
                    cleanUp()
                    callback(Result.failure(e))
                }
            } finally {
                image?.close()
            }
        }, handler)
    }

    private var callbackRegistered = false

    private fun cleanUp() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
    }
}
