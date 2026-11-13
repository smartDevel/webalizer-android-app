package com.smartdev.webalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import kotlin.math.max

class DailyTrafficChartView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberFormat = NumberFormat.getIntegerInstance()
    private var values: List<DailyTraffic> = emptyList()

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

        drawLegend(canvas)

        val chartLeft = dp(48).toFloat()
        val chartTop = dp(88).toFloat()
        val chartRight = width - dp(12).toFloat()
        val chartBottom = height - dp(64).toFloat()
        val chartHeight = chartBottom - chartTop
        val maxValue = values.maxOf { item ->
            max(
                max(item.hits, item.files),
                max(max(item.pages, item.visits), item.suspicious)
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
        val availableBarWidth = ((slotWidth - dp(10)) / 5f).coerceAtLeast(dp(4).toFloat())
        val barWidth = availableBarWidth.coerceAtMost(dp(10).toFloat())
        val groupWidth = barWidth * 5

        values.forEachIndexed { index, item ->
            val groupLeft = chartLeft + index * slotWidth + (slotWidth - groupWidth) / 2f
            drawBar(canvas, groupLeft, item.hits, maxValue, chartTop, chartBottom, barWidth, hitsColor)
            drawBar(
                canvas,
                groupLeft + barWidth,
                item.files,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                filesColor
            )
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
            drawBar(
                canvas,
                groupLeft + barWidth * 3,
                item.visits,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                visitsColor
            )
            drawBar(
                canvas,
                groupLeft + barWidth * 4,
                item.suspicious,
                maxValue,
                chartTop,
                chartBottom,
                barWidth,
                suspiciousColor
            )
            drawSuspiciousValue(
                canvas = canvas,
                centerX = groupLeft + barWidth * 4.5f,
                value = item.suspicious,
                maxValue = maxValue,
                chartTop = chartTop,
                chartBottom = chartBottom
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

    private fun drawLegend(canvas: Canvas) {
        val entries = listOf(
            context.getString(R.string.chart_hits) to hitsColor,
            context.getString(R.string.chart_files) to filesColor,
            context.getString(R.string.chart_pages) to pagesColor,
            context.getString(R.string.chart_visits) to visitsColor,
            context.getString(R.string.chart_suspicious) to suspiciousColor
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
    ) {
        if (value <= 0) return
        val height = (value.toFloat() / maxValue) * (chartBottom - chartTop)
        paint.color = color
        canvas.drawRoundRect(
            RectF(left + dp(1), chartBottom - height, left + barWidth - dp(1), chartBottom),
            dp(2).toFloat(),
            dp(2).toFloat(),
            paint
        )
    }

    private fun drawSuspiciousValue(
        canvas: Canvas,
        centerX: Float,
        value: Int,
        maxValue: Int,
        chartTop: Float,
        chartBottom: Float
    ) {
        if (value <= 0) return
        val barHeight = (value.toFloat() / maxValue) * (chartBottom - chartTop)
        val labelY = (chartBottom - barHeight - dp(4)).coerceAtLeast(chartTop + sp(9))
        paint.color = suspiciousColor
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp(9)
        paint.isFakeBoldText = true
        canvas.drawText(numberFormat.format(value), centerX, labelY, paint)
        paint.isFakeBoldText = false
    }

    private fun formatDay(day: String): String {
        if (day.length < 10) return day
        return "${day.substring(8, 10)}.${day.substring(5, 7)}."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity
}
