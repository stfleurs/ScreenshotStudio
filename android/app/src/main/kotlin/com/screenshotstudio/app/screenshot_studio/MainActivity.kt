package com.screenshotstudio.app.screenshot_studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import com.screenshotstudio.app.screenshot_studio.capture.ScreenCaptureEngine
import com.screenshotstudio.app.screenshot_studio.overlay.FloatingOverlayManager
import com.screenshotstudio.app.screenshot_studio.service.ScreenCaptureForegroundService
import com.screenshotstudio.app.screenshot_studio.stitching.ImageStitchingEngine
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.screenshotstudio.app/capture"
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val TAG = "SSCapture"

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var captureEngine: ScreenCaptureEngine? = null
    private var stitchingEngine: ImageStitchingEngine? = null
    private var overlayManager: FloatingOverlayManager? = null
    private var pendingMethodResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureEngine = ScreenCaptureEngine(this)
        stitchingEngine = ImageStitchingEngine()

        overlayManager = FloatingOverlayManager(
            context = this,
            onCaptureClick = {
                flutterEngine.dartExecutor.binaryMessenger.let {
                    MethodChannel(it, CHANNEL).invokeMethod("onOverlayTriggered", "instant")
                }
            },
            onScrollingCaptureClick = {
                flutterEngine.dartExecutor.binaryMessenger.let {
                    MethodChannel(it, CHANNEL).invokeMethod("onOverlayTriggered", "scrolling")
                }
            }
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestCapturePermission" -> {
                    Log.i(TAG, "requestCapturePermission: existing projection=${mediaProjection != null}")
                    if (mediaProjection != null) {
                        result.success(true)
                    } else {
                        pendingMethodResult = result
                        // Android 14+ requires a running mediaProjection foreground service
                        // *before* getMediaProjection(). Start it up-front so it is already
                        // foregrounded by the time the consent dialog returns.
                        startCaptureService()
                        val captureIntent = mediaProjectionManager?.createScreenCaptureIntent()
                        if (captureIntent != null) {
                            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
                        } else {
                            pendingMethodResult = null
                            result.error("ERROR", "MediaProjectionManager unavailable", null)
                        }
                    }
                }
                "checkOverlayPermission" -> {
                    val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(this)
                    } else {
                        true
                    }
                    result.success(hasOverlayPermission)
                }
                "checkCapturePermission" -> {
                    val granted = mediaProjection != null
                    Log.i(TAG, "checkCapturePermission: $granted")
                    result.success(granted)
                }
                "requestOverlayPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    result.success(true)
                }
                "showFloatingButton" -> {
                    overlayManager?.show()
                    result.success(true)
                }
                "hideFloatingButton" -> {
                    overlayManager?.hide()
                    result.success(true)
                }
                "captureInstant" -> {
                    performInstantCapture(result)
                }
                "stitchImages" -> {
                    val paths = call.argument<List<String>>("paths")
                    if (paths.isNullOrEmpty()) {
                        result.error("INVALID_ARGS", "Frame paths cannot be empty", null)
                    } else {
                        val outputDir = File(cacheDir, "stitched").apply { mkdirs() }
                        val outputFile = File(outputDir, "stitched_${System.currentTimeMillis()}.png")
                        try {
                            val stitchRes = stitchingEngine!!.stitchFrames(paths, outputFile)
                            result.success(
                                mapOf(
                                    "path" to stitchRes.outputPath,
                                    "width" to stitchRes.width,
                                    "height" to stitchRes.height,
                                    "frames" to stitchRes.frameCount
                                )
                            )
                        } catch (e: Exception) {
                            result.error("STITCH_FAILED", e.message, null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun performInstantCapture(result: MethodChannel.Result) {
        val proj = mediaProjection
        Log.i(TAG, "captureInstant: mediaProjection=${proj != null}")
        if (proj == null) {
            result.error("NOT_INITIALIZED", "MediaProjection permission not granted yet", null)
            return
        }

        val outputDir = File(cacheDir, "captures").apply { mkdirs() }
        captureEngine?.captureFrame(proj, outputDir) { res ->
            res.fold(
                onSuccess = { filePath ->
                    result.success(filePath)
                },
                onFailure = { err ->
                    result.error("CAPTURE_ERROR", err.message, null)
                }
            )
        }
    }

    private fun startCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureForegroundService::class.java).apply {
            action = ScreenCaptureForegroundService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startCaptureService failed: $t")
        }
    }

    private fun stopCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureForegroundService::class.java).apply {
            action = ScreenCaptureForegroundService.ACTION_STOP
        }
        try {
            stopService(serviceIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "stopCaptureService failed: $t")
        }
    }

    /**
     * getMediaProjection() throws a SecurityException until the mediaProjection
     * foreground service has actually called startForeground(). Because that happens
     * asynchronously on the main looper, wait (briefly) for it before acquiring.
     */
    private fun acquireProjection(resultCode: Int, data: Intent) {
        val deadline = SystemClock.elapsedRealtime() + 3000

        fun attempt() {
            if (!ScreenCaptureForegroundService.isForegrounded &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Handler(Looper.getMainLooper()).postDelayed({ attempt() }, 50)
                return
            }

            Log.i(TAG, "acquireProjection: service foregrounded=${ScreenCaptureForegroundService.isForegrounded}")
            mediaProjection = try {
                mediaProjectionManager?.getMediaProjection(resultCode, data)
            } catch (t: Throwable) {
                Log.e(TAG, "getMediaProjection threw: $t")
                null
            }
            Log.i(TAG, "acquireProjection: projection acquired=${mediaProjection != null}")
            if (mediaProjection != null) {
                pendingMethodResult?.success(true)
            } else {
                pendingMethodResult?.error(
                    "PROJECTION_NULL",
                    "getMediaProjection returned null (is the foreground service running?)",
                    null
                )
            }
            pendingMethodResult = null
        }

        attempt()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult req=$requestCode resultCode=$resultCode data=${data != null}")
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCaptureService()
                acquireProjection(resultCode, data)
            } else {
                pendingMethodResult?.success(false)
                pendingMethodResult = null
                stopCaptureService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager?.hide()
        val serviceIntent = Intent(this, ScreenCaptureForegroundService::class.java).apply {
            action = ScreenCaptureForegroundService.ACTION_STOP
        }
        stopService(serviceIntent)
        mediaProjection?.stop()
        mediaProjection = null
    }
}
