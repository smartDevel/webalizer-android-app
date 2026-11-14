package com.smartdev.webalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import kotlin.math.max

class DailyTrafficChartView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberFormat = NumberFormat.getIntegerInstance()
    private var values: List<DailyTraffic> = emptyList()
    private val chartTargets = mutableListOf<ChartTarget>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pressedTarget: ChartTarget? = null
    private var onSuspiciousDayClick: ((DailyTraffic) -> Unit)? = null
    private var onFilesDayClick: ((DailyTraffic) -> Unit)? = null
    private var onVisitsDayClick: ((DailyTraffic) -> Unit)? = null

    private val hitsColor = Color.rgb(0, 121, 107)
    private val filesColor = Color.rgb(21, 101, 192)
    private val pagesColor = Color.rgb(0, 172, 193)
    private val visitsColor = Color.rgb(249, 168, 37)
    private val suspiciousColor = Color.rgb(211, 47, 47)
    private val textColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        Color.DKGRAY
    )
    private val gridColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOutlineVariant,
        Color.LTGRAY
    )

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setData(data: List<DailyTraffic>) {
        values = data
        contentDescription = context.getString(
            R.string.chart_accessibility_summary,
            data.size
        )
        requestLayout()
        invalidate()
    }

    fun setOnSuspiciousDayClickListener(listener: (DailyTraffic) -> Unit) {
        onSuspiciousDayClick = listener
        isClickable = true
    }

    fun setOnFilesDayClickListener(listener: (DailyTraffic) -> Unit) {
        onFilesDayClick = listener
        isClickable = true
    }

    fun setOnVisitsDayClickListener(listener: (DailyTraffic) -> Unit) {
        onVisitsDayClick = listener
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max(dp(320), dp(62) + values.size * dp(72))
        val desiredHeight = dp(360)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        chartTargets.clear()

        drawLegend(canvas)

        val chartLeft = dp(48).toFloat()
        val chartTop = dp(88).toFloat()
        val chartRight = width - dp(12).toFloat()
        val chartBottom = height - dp(64).toFloat()
        val chartHeight = chartBottom - chartTop
        val maxValue = values.maxOf { item ->
            max(
                max(item.hits, item.files),
                max(item.pages, item.visits)
            )
        }.coerceAtLeast(1)

        paint.strokeWidth = dp(1).toFloat()
        paint.textSize = sp(10)
        for (step in 0..4) {
            val ratio = step / 4f
            val y = chartBottom - ratio * chartHeight
            paint.color = gridColor
            canvas.drawLine(chartLeft, y, chartRight, y, paint)

            paint.color = textColor
            paint.textAlign = Paint.Align.RIGHT
            val label = numberFormat.format((maxValue * ratio).toInt())
            canvas.drawText(label, chartLeft - dp(6), y + dp(4), paint)
        }

        val slotWidth = (chartRight - chartLeft) / values.size
        val availableBarWidth = ((slotWidth - dp(10)) / 4f).coerceAtLeast(dp(4).toFloat())
        val barWidth = availableBarWidth.coerceAtMost(dp(14).toFloat())
        val groupWidth = barWidth * 4

        values.forEachIndexed { index, item ->
            val groupLeft = chartLeft + index * slotWidth + (slotWidth - groupWidth) / 2f
            val suspiciousBounds = drawStackedHits(
                canvas = canvas,
                left = groupLeft,
                hits = item.hits,
                suspicious = item.suspicious,
                maxValue = maxValue,
                chartTop = chartTop,
                chartBottom = chartBottom,
                barWidth = barWidth
            )
            if (suspiciousBounds != null) {
                chartTargets += ChartTarget(
                    bounds = expandTouchBounds(suspiciousBounds, chartTop, chartBottom),
                    traffic = item,
                    metric = ChartMetric.SUSPICIOUS
                )
            }
            val filesBounds = drawBar(
                canvas,
                groupLeft + barWidth,
                item.files,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                filesColor
            )
            if (filesBounds != null) {
                chartTargets += ChartTarget(
                    bounds = expandTouchBounds(filesBounds, chartTop, chartBottom),
                    traffic = item,
                    metric = ChartMetric.FILES
                )
            }
            drawBar(
                canvas,
                groupLeft + barWidth * 2,
                item.pages,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                pagesColor
            )
            val visitsBounds = drawBar(
                canvas,
                groupLeft + barWidth * 3,
                item.visits,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                visitsColor
            )
            if (visitsBounds != null) {
                chartTargets += ChartTarget(
                    bounds = expandTouchBounds(visitsBounds, chartTop, chartBottom),
                    traffic = item,
                    metric = ChartMetric.VISITS
                )
            }
            drawValueLabel(
                canvas = canvas,
                centerX = groupLeft + barWidth * 3.5f,
                barValue = item.visits,
                labelValue = item.visits,
                maxValue = maxValue,
                chartTop = chartTop,
                chartBottom = chartBottom,
                color = visitsColor
            )

            val labelX = chartLeft + (index + 0.5f) * slotWidth
            val labelY = chartBottom + dp(10)
            paint.color = textColor
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = sp(10)
            canvas.save()
            canvas.rotate(-45f, labelX, labelY)
            canvas.drawText(formatDay(item.day), labelX, labelY, paint)
            canvas.restore()
        }

        paint.color = textColor
        paint.strokeWidth = dp(1).toFloat()
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, paint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = chartTargets.lastOrNull { it.bounds.contains(event.x, event.y) }
                    ?: return false
                downX = event.x
                downY = event.y
                pressedTarget = target
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (
                    kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                ) {
                    pressedTarget = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = pressedTarget
                pressedTarget = null
                if (target != null && target.bounds.contains(event.x, event.y)) {
                    performClick()
                    when (target.metric) {
                        ChartMetric.SUSPICIOUS -> onSuspiciousDayClick?.invoke(target.traffic)
                        ChartMetric.FILES -> onFilesDayClick?.invoke(target.traffic)
                        ChartMetric.VISITS -> onVisitsDayClick?.invoke(target.traffic)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedTarget = null
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawLegend(canvas: Canvas) {
        val entries = listOf(
            context.getString(R.string.chart_hits) to hitsColor,
            context.getString(R.string.chart_suspicious) to suspiciousColor,
            context.getString(R.string.chart_files) to filesColor,
            context.getString(R.string.chart_pages) to pagesColor,
            context.getString(R.string.chart_visits) to visitsColor
        )
        val columnWidth = dp(150)
        entries.forEachIndexed { index, (label, color) ->
            val column = index % 2
            val row = index / 2
            val x = dp(12) + column * columnWidth
            val y = dp(18) + row * dp(24)
            paint.color = color
            canvas.drawRoundRect(
                RectF(x.toFloat(), (y - dp(9)).toFloat(), (x + dp(14)).toFloat(), (y + dp(5)).toFloat()),
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint
            )
            paint.color = textColor
            paint.textSize = sp(12)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, (x + dp(20)).toFloat(), (y + dp(3)).toFloat(), paint)
        }
    }

    private fun drawBar(
        canvas: Canvas,
        left: Float,
        value: Int,
        maxValue: Int,
        chartTop: Float,
        chartBottom: Float,
        barWidth: Float,
        color: Int
    ): RectF? {
        if (value <= 0) return null
        val height = (value.toFloat() / maxValue) * (chartBottom - chartTop)
        val bounds = RectF(
            left + dp(1),
            chartBottom - height,
            left + barWidth - dp(1),
            chartBottom
        )
        paint.color = color
        canvas.drawRoundRect(
            bounds,
            dp(2).toFloat(),
            dp(2).toFloat(),
            paint
        )
        return bounds
    }

    private fun drawStackedHits(
        canvas: Canvas,
        left: Float,
        hits: Int,
        suspicious: Int,
        maxValue: Int,
        chartTop: Float,
        chartBottom: Float,
        barWidth: Float
    ): RectF? {
        if (hits <= 0) return null
        val suspiciousHits = suspicious.coerceIn(0, hits)
        val normalHits = hits - suspiciousHits
        val chartHeight = chartBottom - chartTop
        val normalHeight = (normalHits.toFloat() / maxValue) * chartHeight
        val totalHeight = (hits.toFloat() / maxValue) * chartHeight

        if (normalHits > 0) {
            paint.color = hitsColor
            canvas.drawRoundRect(
                RectF(
                    left + dp(1),
                    chartBottom - normalHeight,
                    left + barWidth - dp(1),
                    chartBottom
                ),
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint
            )
        }
        if (suspiciousHits > 0) {
            val suspiciousBounds = RectF(
                left + dp(1),
                chartBottom - totalHeight,
                left + barWidth - dp(1),
                chartBottom - normalHeight
            )
            paint.color = suspiciousColor
            canvas.drawRoundRect(
                suspiciousBounds,
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint
            )
            drawValueLabel(
                canvas = canvas,
                centerX = left + barWidth / 2f,
                barValue = hits,
                labelValue = suspiciousHits,
                maxValue = maxValue,
                chartTop = chartTop,
                chartBottom = chartBottom,
                color = suspiciousColor
            )
            return suspiciousBounds
        }
        return null
    }

    private fun expandTouchBounds(bounds: RectF, chartTop: Float, chartBottom: Float): RectF {
        val minimumSize = dp(32).toFloat()
        val verticalPadding = ((minimumSize - bounds.height()) / 2f).coerceAtLeast(0f)
        return RectF(
            bounds.left - dp(1),
            (bounds.top - verticalPadding).coerceAtLeast(chartTop),
            bounds.right + dp(1),
            (bounds.bottom + verticalPadding).coerceAtMost(chartBottom)
        )
    }

    private fun drawValueLabel(
        canvas: Canvas,
        centerX: Float,
        barValue: Int,
        labelValue: Int,
        maxValue: Int,
        chartTop: Float,
        chartBottom: Float,
        color: Int
    ) {
        if (barValue <= 0) return
        val barHeight = (barValue.toFloat() / maxValue) * (chartBottom - chartTop)
        val labelY = (chartBottom - barHeight - dp(4)).coerceAtLeast(chartTop + sp(9))
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp(9)
        paint.isFakeBoldText = true
        canvas.drawText(numberFormat.format(labelValue), centerX, labelY, paint)
        paint.isFakeBoldText = false
    }

    private fun formatDay(day: String): String {
        if (day.length < 10) return day
        return "${day.substring(8, 10)}.${day.substring(5, 7)}."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity

    private data class ChartTarget(
        val bounds: RectF,
        val traffic: DailyTraffic,
        val metric: ChartMetric
    )

    private enum class ChartMetric {
        SUSPICIOUS,
        FILES,
        VISITS
    }
}
