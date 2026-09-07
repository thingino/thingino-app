package com.thingino.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A soft halo in the accent colour around a rounded rectangle inset from this
 * view's bounds by [BLEED_DP]. Meant to sit behind a button of the same corner
 * radius, sized so that the inner rectangle coincides with the button's visible
 * face: the halo bleeds outward from the button's edge and the inside stays
 * clear, so the button's own fill shows through unchanged.
 *
 * Software-rendered on purpose. BlurMaskFilter is ignored by the hardware
 * canvas, and a software layer rasterises this view once and then composites
 * the bitmap, so animating its alpha costs nothing per frame. The layer is the
 * view's own size, which is why the bleed must lie inside the bounds rather
 * than being drawn past them.
 */
class GlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bleed = BLEED_DP * density
    private val radius = CORNER_DP * density
    private val face = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        // The blur's visible reach is roughly 1.7x its radius, kept inside the
        // bleed so the tail is not cut off at the layer's edge.
        maskFilter = BlurMaskFilter(bleed * 0.55f, BlurMaskFilter.Blur.OUTER)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        face.set(bleed, bleed, width - bleed, height - bleed)
        canvas.drawRoundRect(face, radius, radius, paint)
    }

    companion object {
        /** Distance from the view's edge to the face it glows around, in dp. */
        const val BLEED_DP = 18
        private const val CORNER_DP = 8
    }
}
