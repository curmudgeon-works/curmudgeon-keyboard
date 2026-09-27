// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An icon with a small word under it, as one drawable: the toolbar's clipboard key says "Clipboard". The icon is
 * drawn a little smaller so icon and word together are no taller than a plain icon would be, and both take the
 * view's color filter (the toolbar tints its keys that way).
 */
class LabeledIconDrawable(private val icon: Drawable, private val label: String, textSizePx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE // white, so the tint filter decides the color like it does for the icon
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
    }
    private val textBounds = Rect().also { paint.getTextBounds(label, 0, label.length, it) }
    private val gap = (textSizePx * 0.15f).roundToInt()
    private val iconScale = 0.72f
    private val iconWidth = (icon.intrinsicWidth * iconScale).roundToInt()
    private val iconHeight = (icon.intrinsicHeight * iconScale).roundToInt()

    override fun getIntrinsicWidth() = max(icon.intrinsicWidth, textBounds.width() + 2)
    override fun getIntrinsicHeight() = max(icon.intrinsicHeight, iconHeight + gap + textBounds.height())

    override fun onBoundsChange(bounds: Rect) {
        val contentHeight = iconHeight + gap + textBounds.height()
        val top = bounds.centerY() - contentHeight / 2
        val left = bounds.centerX() - iconWidth / 2
        icon.setBounds(left, top, left + iconWidth, top + iconHeight)
    }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        val baseline = icon.bounds.bottom + gap + textBounds.height() - textBounds.bottom
        canvas.drawText(label, bounds.exactCenterX(), baseline.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    // the toolbar tints its keys with a state list (normal / activated): the icon takes it as is, the word
    // is drawn in the list's colour for the current state
    private var tint: ColorStateList? = null

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        icon.setTintList(tint)
        applyTint()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        icon.setTintMode(tintMode)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        icon.state = state
        applyTint()
        return true
    }

    private fun applyTint() {
        val list = tint
        paint.color = if (list == null) Color.WHITE else list.getColorForState(state, list.defaultColor)
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
