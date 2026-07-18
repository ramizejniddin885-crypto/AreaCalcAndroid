package com.areacalc.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * «Математический» индикатор загрузки: рисует анимированную кривую Лиссажу
 * с неоновым градиентом и бегущей точкой. Ощущение «вычисления».
 */
class MathSpinnerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22E0C8") }
    private val path = Path()

    private var phase = 0f
    private var anim: ValueAnimator? = null

    private val primary = Color.parseColor("#7A5CFF")
    private val accent = Color.parseColor("#22E0C8")

    fun start() {
        if (anim?.isRunning == true) return
        visibility = VISIBLE
        anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    fun stop() {
        anim?.cancel(); anim = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f * 0.72f
        if (r <= 0) return

        curvePaint.shader = LinearGradient(0f, 0f, w, h, primary, accent, Shader.TileMode.CLAMP)

        // Кривая Лиссажу x=sin(a t + phase), y=sin(b t)
        val a = 3.0; val b = 2.0
        val n = 260
        path.reset()
        for (i in 0..n) {
            val t = i.toDouble() / n * 2 * PI
            val x = cx + r * sin(a * t + phase).toFloat()
            val y = cy + r * sin(b * t).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)

        // Бегущая точка по кривой
        val td = phase.toDouble()
        val dx = cx + r * sin(a * td + phase).toFloat()
        val dy = cy + r * sin(b * td).toFloat()
        canvas.drawCircle(dx, dy, 9f, dotPaint)

        // Пульсирующее кольцо-акцент
        val ringAlpha = (120 + 80 * cos(phase * 2)).toInt().coerceIn(0, 255)
        dotPaint.alpha = ringAlpha
        canvas.drawCircle(cx, cy, 6f, dotPaint)
        dotPaint.alpha = 255
    }
}
