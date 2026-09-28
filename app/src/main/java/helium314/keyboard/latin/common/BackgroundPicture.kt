// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import androidx.core.graphics.createBitmap
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * How a background picture sits on the keyboard. Normally it is scaled without distortion: at [zoom] 1 just enough to
 * cover the keyboard (the edges that don't fit are cut off), below 1 smaller (the rest is the theme's background colour),
 * the picture point ([cx], [cy], fractions of its width and height) at the keyboard's centre as far as the edges allow.
 * [stretch]: the whole picture squeezed onto the keyboard, as before 0.1.004.
 * Kept in a file next to the picture ([fileFor]), so themes, Save / Discard and backups carry it with the picture;
 * no file = cover, centred. Relative to the picture, so it fits every keyboard height (numbers row, toolbar, landscape).
 */
data class PictureFraming(val stretch: Boolean = false, val zoom: Float = 1f, val cx: Float = 0.5f, val cy: Float = 0.5f) {

    /** Where the picture ([pw] × [ph]) goes on a [w] × [h] keyboard. */
    fun place(pw: Int, ph: Int, w: Float, h: Float): RectF {
        if (stretch || pw <= 0 || ph <= 0) return RectF(0f, 0f, w, h)
        val s = cover(pw, ph, w, h) * zoom
        val sw = pw * s
        val sh = ph * s
        val left = axis(sw, w, cx)
        val top = axis(sh, h, cy)
        return RectF(left, top, left + sw, top + sh)
    }

    /** The same placement with the centre point where the edges put it (a drag past an edge doesn't pile up). */
    fun settled(pw: Int, ph: Int, w: Float, h: Float): PictureFraming {
        if (stretch) return this
        val r = place(pw, ph, w, h)
        return copy(cx = (w / 2 - r.left) / r.width(), cy = (h / 2 - r.top) / r.height())
    }

    fun write(file: File) {
        file.writeText(JSONObject().put("stretch", stretch).put("zoom", zoom.toDouble())
            .put("cx", cx.toDouble()).put("cy", cy.toDouble()).toString())
    }

    companion object {
        const val MAX_ZOOM = 5f

        /** The scale at which the picture just covers the keyboard (zoom 1). */
        fun cover(pw: Int, ph: Int, w: Float, h: Float) = max(w / pw, h / ph)

        /** The smallest zoom: the whole picture shows. */
        fun minZoom(pw: Int, ph: Int, w: Float, h: Float) = min(w / pw, h / ph) / cover(pw, ph, w, h)

        // bigger than the keyboard: its edges stay outside; smaller: it stays inside
        private fun axis(size: Float, view: Float, c: Float): Float {
            val start = view / 2 - c * size
            return if (size >= view) start.coerceIn(view - size, 0f) else start.coerceIn(0f, view - size)
        }

        fun fileFor(picture: File) = File(picture.path + ".framing")

        fun read(picture: File): PictureFraming = runCatching {
            val o = JSONObject(fileFor(picture).readText())
            PictureFraming(o.optBoolean("stretch"), o.optDouble("zoom", 1.0).toFloat(),
                o.optDouble("cx", 0.5).toFloat(), o.optDouble("cy", 0.5).toFloat())
        }.getOrDefault(PictureFraming())

        /** The picture upright (camera pictures carry their rotation separately) and at most [maxSide] pixels on its
         *  long side (a 50 MP photo would be 200 MB in memory and too big for the screen to draw); null if unreadable. */
        fun decode(file: File, maxSide: Int = 4096): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val rotation = runCatching {
                when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            if (rotation == 0f) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        }
    }
}

/**
 * A background picture drawn with its [framing] at whatever size the view has; [band] fills what the picture doesn't
 * cover (zoomed out). Rendered once per size into a bitmap of the view's size. One instance per view ([forView]).
 */
class FramedPicture(val bitmap: Bitmap, val framing: PictureFraming, private val band: Int = Color.TRANSPARENT) : Drawable() {
    private var rendered: Bitmap? = null

    /** A copy for one view, the uncovered parts in [bandColor]. */
    fun forView(bandColor: Int) = FramedPicture(bitmap, framing, bandColor)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        var r = rendered
        if (r == null || r.width != b.width() || r.height != b.height()) {
            r = createBitmap(b.width(), b.height())
            Canvas(r).apply {
                drawColor(band)
                drawBitmap(bitmap, null, framing.place(bitmap.width, bitmap.height, b.width().toFloat(), b.height().toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            rendered = r
        }
        canvas.drawBitmap(r, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
