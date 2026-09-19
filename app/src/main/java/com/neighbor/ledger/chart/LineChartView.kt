package com.neighbor.ledger.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.max

/**
 * 极简折线图（MIUI8 扁平风格，自绘，无第三方图表依赖）。
 *
 * 样式（第三章第 3 条）：
 * - 灰色虚线 = 日消费限额；
 * - 彩色实线 = 每日真实消费（节约段绿色、超支段红色）。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var series: ChartSeries? = null

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        strokeWidth = dp(1f)
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = 0xFF9E9E9E.toInt()
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f)
        color = 0xFF9E9E9E.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val green = 0xFF4CAF50.toInt()
    private val red = 0xFFE53935.toInt()

    fun setSeries(s: ChartSeries?) {
        series = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padLeft = dp(34f)
        val padRight = dp(12f)
        val padTop = dp(16f)
        val padBottom = dp(24f)

        val s = series
        if (s == null || s.points.isEmpty()) {
            canvas.drawText("暂无数据", w / 2f, h / 2f, emptyPaint)
            return
        }

        // 坐标轴
        canvas.drawLine(padLeft, h - padBottom, w - padRight, h - padBottom, axisPaint)
        canvas.drawLine(padLeft, padTop, padLeft, h - padBottom, axisPaint)

        val n = s.points.size
        val plotW = w - padLeft - padRight
        val plotH = h - padTop - padBottom

        val maxV = max(s.points.maxOf { max(it.expense, it.limit) }, 0.01)

        fun xAt(i: Int): Float {
            val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
            return padLeft + plotW * t
        }

        fun yAt(v: Double): Float =
            padTop + plotH * (1.0 - (v / maxV)).toFloat()

        // 限额虚线（灰）：仅连接已知限额（limit > 0）的相邻天，未知限额（0）断开，避免画到底部误导。
        val limitPath = android.graphics.Path()
        var drawing = false
        for (i in s.points.indices) {
            val lim = s.points[i].limit
            if (lim <= 0.0) {
                drawing = false
                continue
            }
            val x = xAt(i)
            val y = yAt(lim)
            if (!drawing) {
                limitPath.moveTo(x, y)
                drawing = true
            } else {
                limitPath.lineTo(x, y)
            }
        }
        canvas.drawPath(limitPath, limitPaint)

        // 消费实线，节约段绿、超支段红
        for (i in s.points.indices) {
            val p = s.points[i]
            val x = xAt(i)
            val y = yAt(p.expense)
            dotPaint.color = if (p.expense > p.limit) red else green
            canvas.drawCircle(x, y, dp(3f), dotPaint)
            if (i < n - 1) {
                val q = s.points[i + 1]
                linePaint.color = if (p.expense > p.limit || q.expense > q.limit) red else green
                canvas.drawLine(x, y, xAt(i + 1), yAt(q.expense), linePaint)
            }
        }

        // 首尾 x 轴标签
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${s.points.first().day}", padLeft, h - dp(6f), labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${s.points.last().day}", w - padRight, h - dp(6f), labelPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}