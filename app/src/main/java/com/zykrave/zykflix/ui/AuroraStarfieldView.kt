package com.zykrave.zykflix.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Random
import kotlin.math.sin

/**
 * Custom View that draws an animated aurora + starfield background effect using Canvas.
 */
class AuroraStarfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Int,
    )

    private class AuroraBand(
        val path: Path = Path(),
        val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        },
    )

    private var animator: ValueAnimator? = null
    private var phase: Float = 0f
    private var cachedW: Float = 0f
    private var cachedH: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6F1FB")
        style = Paint.Style.FILL
    }

    private val band1 = AuroraBand()
    private val band2 = AuroraBand()
    private val band3 = AuroraBand()

    private val stars = mutableListOf<Star>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if ((w <= 0) || (h <= 0)) return

        val fw = w.toFloat()
        val fh = h.toFloat()

        cachedW = fw
        cachedH = fh

        // 1. Background color (#000000 solid black)
        bgPaint.shader = null
        bgPaint.color = Color.parseColor("#000000")

        // 2. Aurora Bands
        val band1Color = Color.parseColor("#1D9E75") // Teal-green
        val band2Color = Color.parseColor("#378ADD") // Blue
        val band3Color = Color.parseColor("#4A6FEE") // Blue-violet

        setupBand(band1, fw, fh, 0.28f, 0.15f, band1Color, 0x90, phase, 0f)
        setupBand(band2, fw, fh, 0.40f, 0.15f, band2Color, 0x85, phase, 2f)
        setupBand(band3, fw, fh, 0.52f, 0.15f, band3Color, 0x78, phase, 4f)

        // 3. Starfield (~90 stars, top 75% height, fixed seed 77)
        stars.clear()
        val density = resources.displayMetrics.density
        val random = Random(77)
        val starCount = 90

        repeat(starCount) {
            val sx = random.nextFloat() * fw
            val sy = random.nextFloat() * (fh * 0.75f)
            val radiusDp = 0.5f + random.nextFloat() * (1.4f - 0.5f)
            val radiusPx = radiusDp * density
            val alphaFraction = 0.3f + random.nextFloat() * (0.8f - 0.3f)
            val alphaInt = (alphaFraction * 255).toInt().coerceIn(0, 255)

            stars.add(Star(sx, sy, radiusPx, alphaInt))
        }
    }

    private fun setupBand(
        band: AuroraBand,
        w: Float,
        h: Float,
        centerRatio: Float,
        halfSpanRatio: Float,
        color: Int,
        maxAlpha: Int,
        phase: Float,
        phaseOffset: Float
    ) {
        val topY = (centerRatio - halfSpanRatio) * h
        val bottomY = (centerRatio + halfSpanRatio) * h

        val startX = 0.15f * w
        val endX = 0.85f * w
        val bandWidth = endX - startX

        val colorTransparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        val colorOpaque = Color.argb(maxAlpha, Color.red(color), Color.green(color), Color.blue(color))

        val verticalGradient = LinearGradient(
            0f, topY,
            0f, bottomY,
            intArrayOf(colorTransparent, colorOpaque, colorTransparent),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val horizontalFade = LinearGradient(
            startX, 0f,
            endX, 0f,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        band.paint.shader = ComposeShader(
            verticalGradient,
            horizontalFade,
            PorterDuff.Mode.DST_IN
        )

        val p = band.path
        p.reset()

        val currentPhase = phase + phaseOffset
        val wave1 = sin(currentPhase) * 0.03f * h
        val wave2 = sin(currentPhase + 1.2f) * 0.03f * h
        val wave3 = sin(currentPhase + 2.4f) * 0.03f * h

        // Top wave curve across middle 70% width
        val cp1x = startX + 0.33f * bandWidth
        val cp1y = topY + wave1
        val cp2x = startX + 0.66f * bandWidth
        val cp2y = topY + wave2
        val endY = topY + wave3

        p.moveTo(startX, topY + wave3)
        p.cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)

        // Bottom wave curve back to startX
        val bEndY = bottomY + wave3
        val bCp2x = startX + 0.66f * bandWidth
        val bCp2y = bottomY + wave2
        val bCp1x = startX + 0.33f * bandWidth
        val bCp1y = bottomY + wave1

        p.lineTo(endX, bEndY)
        p.cubicTo(bCp2x, bCp2y, bCp1x, bCp1y, startX, bottomY + wave3)
        p.close()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                if ((cachedW > 0f) && (cachedH > 0f)) {
                    setupBand(band1, cachedW, cachedH, 0.28f, 0.15f, Color.parseColor("#1D9E75"), 0x90, phase, 0f)
                    setupBand(band2, cachedW, cachedH, 0.40f, 0.15f, Color.parseColor("#378ADD"), 0x85, phase, 2f)
                    setupBand(band3, cachedW, cachedH, 0.52f, 0.15f, Color.parseColor("#4A6FEE"), 0x78, phase, 4f)
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw aurora bands
        canvas.drawPath(band1.path, band1.paint)
        canvas.drawPath(band2.path, band2.paint)
        canvas.drawPath(band3.path, band3.paint)

        // Draw stars
        for (star in stars) {
            starPaint.alpha = star.alpha
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }
    }
}
