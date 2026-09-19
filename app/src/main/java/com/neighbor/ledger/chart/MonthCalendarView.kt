package com.neighbor.ledger.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.abs
import com.neighbor.ledger.R
import com.neighbor.ledger.budget.DayLevel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 自绘月历（MIUI8 扁平风格，无第三方依赖）。
 *
 * 格子颜色（第三章第 3 条）：当日消费 ≤ 80% → 绿；80%-100% → 白灰；>100% → 红。
 * 未来日期 / 无历史限额的旧日期 → 中性不填色。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var yearMonth: YearMonth = YearMonth.now()
    private var selected: LocalDate? = null
    private var today: LocalDate = LocalDate.now()
    private var expenseByDay: Map<Int, Double> = emptyMap()
    private var limitByDay: Map<Int, Double> = emptyMap()
    private var todayLimit: Double = 0.0

    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    var onDateSelected: ((LocalDate) -> Unit)? = null
    var onMonthSwipe: ((Int) -> Unit)? = null

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }

    private val calGreen = ContextCompat.getColor(context, R.color.cal_green)
    private val calRed = ContextCompat.getColor(context, R.color.cal_red)
    private val calNeutral = ContextCompat.getColor(context, R.color.cal_neutral)
    private val calWarn = ContextCompat.getColor(context, R.color.cal_warn)
    private val cellRadius = dp(5f)
    private val todayColor = ContextCompat.getColor(context, R.color.brand)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); textAlign = Paint.Align.CENTER; color = textSecondary
    }

    private val weekHeaders = listOf("一", "二", "三", "四", "五", "六", "日")

    fun setData(
        ym: YearMonth,
        selectedDate: LocalDate?,
        todayDate: LocalDate,
        expenseMap: Map<Int, Double>,
        limitMap: Map<Int, Double>,
        todayLimitValue: Double
    ) {
        yearMonth = ym
        selected = selectedDate
        today = todayDate
        expenseByDay = expenseMap
        limitByDay = limitMap
        todayLimit = todayLimitValue
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cols = 7
        val cellW = w / cols
        val headerH = dp(24f)
        val daysInMonth = yearMonth.lengthOfMonth()
        // 以周一为首列
        val firstDay = yearMonth.atDay(1).dayOfWeek
        val offset = firstDay.value - DayOfWeek.MONDAY.value // 0..6

        // 星期标题
        for (c in 0 until cols) {
            val x = cellW * c + cellW / 2
            canvas.drawText(weekHeaders[c], x, headerH * 0.6f, headerPaint)
        }

        val totalRows = ceilDiv(offset + daysInMonth, cols)
        val bodyH = h - headerH
        val cellH = bodyH / totalRows

        for (i in 0 until daysInMonth) {
            val index = offset + i
            val col = index % cols
            val row = index / cols
            val cx = col * cellW + cellW / 2
            val cy = headerH + row * cellH + cellH / 2
            val date = yearMonth.atDay(i + 1)

            val expense = expenseByDay[i + 1] ?: 0.0
            val limit = limitByDay[i + 1]

            // 背景填充（只对今天及过去、且有数据基准的日子着色）
            if (!date.isAfter(today)) {
                val effectiveLimit = limit ?: if (date == today) todayLimit else 0.0
                if (effectiveLimit > 0.0 || expense > 0.0) {
                    val level = when {
                        effectiveLimit <= 0.0 -> if (expense > 0) DayLevel.RED else DayLevel.WHITE
                        else -> {
                            val r = expense / effectiveLimit
                            when {
                                r <= 0.8 -> DayLevel.GREEN
                                r <= 1.0 -> DayLevel.WHITE
                                else -> DayLevel.RED
                            }
                        }
                    }
                    val color = when (level) {
                        DayLevel.GREEN -> calGreen
                        DayLevel.RED -> calRed
                        DayLevel.WHITE -> calWarn
                    }
                    fillPaint.color = color
                    val half = dp(14f)
                    // 浅色小圆角方框，而不是深色实心圆球，保证日期清晰可读
                    canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, cellRadius, cellRadius, fillPaint)
                }
            }

            // 今天 / 选中描边（同样用小圆角方框）
            val isToday = date == today
            val isSelected = date == selected
            if (isToday) {
                strokePaint.color = todayColor
                val half = dp(15f)
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, cellRadius, cellRadius, strokePaint)
            }
            if (isSelected) {
                strokePaint.color = ContextCompat.getColor(context, R.color.selection)
                val half = dp(17f)
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, cellRadius, cellRadius, strokePaint)
            }

            // 数字
            val textColor = when {
                // 选中的日期永远高亮（品牌蓝），不因未来/过去而变灰
                isSelected -> todayColor
                date.isAfter(today) -> textSecondary
                isToday -> todayColor
                else -> textPrimary
            }
            dayPaint.color = textColor
            canvas.drawText("${i + 1}", cx, cy - (dayPaint.ascent() + dayPaint.descent()) / 2f, dayPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            // 必须消费 DOWN，否则系统不会把 UP 派发给本 View——这正是之前点不中的根因。
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiping = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!swiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (swiping) translationX = dx * 0.5f // 日历卡片跟着手指
                true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (swiping && abs(dx) >= touchSlop) {
                    // 左滑 → 下一个月；右滑 → 上一个月
                    onMonthSwipe?.invoke(if (dx < 0) 1 else -1)
                } else if (!swiping) {
                    handleTap(event.x, event.y)
                }
                swiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).setDuration(120L).start()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                swiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).setDuration(120L).start()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    private fun handleTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cols = 7
        val cellW = w / cols
        val headerH = dp(24f)
        val firstDay = yearMonth.atDay(1).dayOfWeek
        val offset = firstDay.value - DayOfWeek.MONDAY.value
        val totalRows = ceilDiv(offset + yearMonth.lengthOfMonth(), cols)
        val cellH = (h - headerH) / totalRows

        val col = (x / cellW).toInt()
        val row = ((y - headerH) / cellH).toInt()
        if (col in 0 until cols && row in 0 until totalRows) {
            val index = row * cols + col - offset
            if (index in 0 until yearMonth.lengthOfMonth()) {
                onDateSelected?.invoke(yearMonth.atDay(index + 1))
            }
        }
        performClick()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}