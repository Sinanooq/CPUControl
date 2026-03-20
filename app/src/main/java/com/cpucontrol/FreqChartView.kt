package com.cpucontrol

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Gerçek zamanlı CPU frekans çizgi grafiği.
 * 3 çizgi: Little (yeşil), Big (cyan), Prime (mor)
 * Son MAX_POINTS nokta gösterilir.
 */
class FreqChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_POINTS = 60
    }

    private val littleData = ArrayDeque<Float>(MAX_POINTS)
    private val bigData    = ArrayDeque<Float>(MAX_POINTS)
    private val primeData  = ArrayDeque<Float>(MAX_POINTS)

    // Maksimum frekans (MHz) — normalize için
    private var maxFreq = 3000f

    private val paintLittle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val paintBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val paintPrime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CE93D8")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val paintFillLittle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A00E676")
        style = Paint.Style.FILL
    }
    private val paintFillBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A00BCD4")
        style = Paint.Style.FILL
    }
    private val paintFillPrime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1ACE93D8")
        style = Paint.Style.FILL
    }

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        textSize = 24f
    }

    private val path = Path()

    /** MHz cinsinden yeni nokta ekle */
    fun addPoint(littleMhz: Float, bigMhz: Float, primeMhz: Float) {
        fun ArrayDeque<Float>.push(v: Float) {
            if (size >= MAX_POINTS) removeFirst()
            addLast(v)
        }
        littleData.push(littleMhz)
        bigData.push(bigMhz)
        primeData.push(primeMhz)

        // Dinamik max
        val m = maxOf(littleMhz, bigMhz, primeMhz, 100f)
        if (m > maxFreq) maxFreq = m
        invalidate()
    }

    fun clear() {
        littleData.clear(); bigData.clear(); primeData.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val padL = 8f; val padR = 8f; val padT = 8f; val padB = 24f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Grid çizgileri (4 yatay)
        for (i in 1..3) {
            val y = padT + chartH * (1f - i / 4f)
            canvas.drawLine(padL, y, padL + chartW, y, paintGrid)
            val label = "${(maxFreq * i / 4).toInt()} MHz"
            canvas.drawText(label, padL + 4f, y - 4f, paintLabel)
        }

        // Her veri seti için çizgi + fill
        drawSeries(canvas, littleData, chartW, chartH, padL, padT, padB, paintLittle, paintFillLittle)
        drawSeries(canvas, bigData,    chartW, chartH, padL, padT, padB, paintBig,    paintFillBig)
        drawSeries(canvas, primeData,  chartW, chartH, padL, padT, padB, paintPrime,  paintFillPrime)

        // Legend
        val legendY = h - 4f
        val dotR = 5f
        canvas.drawCircle(padL + 8f,  legendY, dotR, paintLittle.apply { style = Paint.Style.FILL })
        canvas.drawText("Little", padL + 18f, legendY + 4f, paintLabel)
        canvas.drawCircle(padL + 80f, legendY, dotR, paintBig.apply { style = Paint.Style.FILL })
        canvas.drawText("Big",    padL + 90f, legendY + 4f, paintLabel)
        canvas.drawCircle(padL + 140f,legendY, dotR, paintPrime.apply { style = Paint.Style.FILL })
        canvas.drawText("Prime",  padL + 150f,legendY + 4f, paintLabel)

        // Stilleri geri al
        paintLittle.style = Paint.Style.STROKE
        paintBig.style    = Paint.Style.STROKE
        paintPrime.style  = Paint.Style.STROKE
    }

    private fun drawSeries(
        canvas: Canvas,
        data: ArrayDeque<Float>,
        chartW: Float, chartH: Float,
        padL: Float, padT: Float, padB: Float,
        linePaint: Paint, fillPaint: Paint
    ) {
        if (data.size < 2) return
        val step = chartW / (MAX_POINTS - 1).toFloat()
        val startX = padL + (MAX_POINTS - data.size) * step

        path.reset()
        data.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = padT + chartH * (1f - (v / maxFreq).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Fill
        val fillPath = Path(path)
        val lastX = startX + (data.size - 1) * step
        val bottom = padT + chartH
        fillPath.lineTo(lastX, bottom)
        fillPath.lineTo(startX, bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Line
        canvas.drawPath(path, linePaint)
    }
}
