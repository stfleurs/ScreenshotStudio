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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class ScreenCaptureEngine(private val context: Context) {

    private val TAG = "SSCapture"
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private var activeProjection: MediaProjection? = null

    fun startSession(mediaProjection: MediaProjection) {
        stopSession()
        activeProjection = mediaProjection

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        val density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            density = context.resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }

        Log.i(TAG, "startSession: display resolution ${width}x$height, density=$density")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            var image: Image? = null
            try {
                image = ir.acquireLatestImage()
                if (image != null) {
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

                    val finalBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = finalBitmap
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring image from ImageReader: ${e.message}")
            } finally {
                image?.close()
            }
        }, handler)

        try {
            val display = mediaProjection.createVirtualDisplay(
                "ScreenshotStudio_VirtualDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            virtualDisplay = display
            Log.i(TAG, "VirtualDisplay successfully created")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create VirtualDisplay", t)
            stopSession()
            throw t
        }
    }

    fun captureFrame(outputDir: File, callback: (Result<String>) -> Unit) {
        // If we already have a latestBitmap in buffer, save it right away
        var bmp: Bitmap? = null
        synchronized(bitmapLock) {
            if (latestBitmap != null && !latestBitmap!!.isRecycled) {
                bmp = latestBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
            }
        }

        if (bmp != null) {
            saveBitmapToFile(bmp!!, outputDir, callback)
            return
        }

        // Otherwise, poll up to 2.5 seconds for the first incoming frame
        var captured = false
        val startTime = SystemClock.elapsedRealtime()
        val timeout = 2500L

        fun pollFrame() {
            if (captured) return

            synchronized(bitmapLock) {
                if (latestBitmap != null && !latestBitmap!!.isRecycled) {
                    bmp = latestBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                }
            }

            if (bmp != null) {
                captured = true
                saveBitmapToFile(bmp!!, outputDir, callback)
            } else if (SystemClock.elapsedRealtime() - startTime > timeout) {
                captured = true
                Log.e(TAG, "Timed out waiting for frame from ImageReader")
                callback(Result.failure(RuntimeException("Timed out waiting for screen frame")))
            } else {
                handler.postDelayed({ pollFrame() }, 50)
            }
        }

        pollFrame()
    }

    private fun saveBitmapToFile(bitmap: Bitmap, outputDir: File, callback: (Result<String>) -> Unit) {
        try {
            val fileName = "capture_${System.currentTimeMillis()}.png"
            val file = File(outputDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Screenshot saved successfully: ${file.absolutePath}")
            callback(Result.success(file.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot bitmap", e)
            callback(Result.failure(e))
        }
    }

    fun isSessionActive(): Boolean {
        return virtualDisplay != null && activeProjection != null
    }

    fun stopSession() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = null
            }
            activeProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Exception during stopSession: ${e.message}")
        }
    }
}

