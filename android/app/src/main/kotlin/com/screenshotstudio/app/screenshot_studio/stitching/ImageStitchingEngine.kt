package com.screenshotstudio.app.screenshot_studio.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class StitchSegment(
    val imagePath: String,
    val overlapOffset: Int // Number of pixels overlapping with previous image
)

data class StitchResult(
    val outputPath: String,
    val width: Int,
    val height: Int,
    val frameCount: Int
)

class ImageStitchingEngine {

    /**
     * Calculates vertical overlap between [topBitmap] and [bottomBitmap].
     * Uses normalized difference / pixel row matching with sampling for high speed & accuracy.
     */
    fun findVerticalOverlap(
        topBitmap: Bitmap,
        bottomBitmap: Bitmap,
        minOverlapPercent: Float = 0.10f,
        maxOverlapPercent: Float = 0.90f
    ): Int {
        val width = topBitmap.width
        val height = topBitmap.height

        val minOverlap = (height * minOverlapPercent).toInt()
        val maxOverlap = (height * maxOverlapPercent).toInt()

        val sampleStepX = max(1, width / 60) // Sample 60 vertical lines across the image
        var bestOverlap = 0
        var lowestDiff = Long.MAX_VALUE

        // Scan candidate overlap offsets
        for (overlap in minOverlap..maxOverlap step 2) {
            var diff: Long = 0
            val sampleRows = 20
            val rowStep = max(1, overlap / sampleRows)
            var comparisons = 0

            for (y in 0 until overlap step rowStep) {
                val topY = height - overlap + y
                val bottomY = y

                for (x in 0 until width step sampleStepX) {
                    val p1 = topBitmap.getPixel(x, topY)
                    val p2 = bottomBitmap.getPixel(x, bottomY)

                    val rDiff = abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff))
                    val gDiff = abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff))
                    val bDiff = abs((p1 and 0xff) - (p2 and 0xff))

                    diff += (rDiff + gDiff + bDiff)
                    comparisons++
                }
            }

            val normalizedDiff = if (comparisons > 0) diff / comparisons else Long.MAX_VALUE
            if (normalizedDiff < lowestDiff) {
                lowestDiff = normalizedDiff
                bestOverlap = overlap
            }
        }

        // If diff threshold is within acceptable bounds, return bestOverlap; otherwise fallback
        return if (lowestDiff < 30) bestOverlap else (height * 0.25f).toInt()
    }

    /**
     * Stitches multiple captured frame images vertically into a single seamless long screenshot.
     */
    fun stitchFrames(framePaths: List<String>, outputFile: File): StitchResult {
        require(framePaths.isNotEmpty()) { "Cannot stitch empty frame list" }

        if (framePaths.size == 1) {
            val src = File(framePaths[0])
            src.copyTo(outputFile, overwrite = true)
            val bmp = BitmapFactory.decodeFile(outputFile.absolutePath)
            return StitchResult(outputFile.absolutePath, bmp.width, bmp.height, 1)
        }

        val bitmaps = framePaths.map { BitmapFactory.decodeFile(it) }
        val width = bitmaps[0].width
        val overlaps = mutableListOf<Int>()

        // 1. Calculate overlaps between consecutive frames
        var totalHeight = bitmaps[0].height
        for (i in 0 until bitmaps.size - 1) {
            val overlap = findVerticalOverlap(bitmaps[i], bitmaps[i + 1])
            overlaps.add(overlap)
            val addedHeight = bitmaps[i + 1].height - overlap
            totalHeight += addedHeight
        }

        // 2. Allocate canvas bitmap
        val stitchedBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitchedBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 3. Draw first frame
        canvas.drawBitmap(bitmaps[0], 0f, 0f, paint)
        var currentY = bitmaps[0].height

        // 4. Draw subsequent frames cropping the overlapping portion
        for (i in 0 until overlaps.size) {
            val overlap = overlaps[i]
            val nextBmp = bitmaps[i + 1]
            val uniqueHeight = nextBmp.height - overlap

            val srcRect = android.graphics.Rect(0, overlap, width, nextBmp.height)
            val dstRect = android.graphics.Rect(0, currentY - overlap + overlap, width, currentY + uniqueHeight)

            canvas.drawBitmap(nextBmp, srcRect, dstRect, paint)
            currentY += uniqueHeight
        }

        // 5. Save output PNG
        FileOutputStream(outputFile).use { out ->
            stitchedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return StitchResult(outputFile.absolutePath, width, totalHeight, framePaths.size)
    }
}
