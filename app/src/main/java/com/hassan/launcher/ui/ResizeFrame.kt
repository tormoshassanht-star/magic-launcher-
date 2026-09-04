package com.hassan.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import com.hassan.launcher.R
import kotlin.math.hypot

class ResizeFrame(context: Context) : View(context) {

    companion object {
        const val NONE = 0
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 3
        const val BOTTOM = 4
    }

    var rect: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val radius = context.dp(9).toFloat()
    private val hitRadius = context.dp(30).toFloat()

    private fun handleCenters(r: RectF): List<Pair<Float, Float>> = listOf(
        r.left to r.centerY(),
        r.centerX() to r.top,
        r.right to r.centerY(),
        r.centerX() to r.bottom,
    )

    fun handleAt(x: Float, y: Float): Int {
        val r = rect ?: return NONE
        handleCenters(r).forEachIndexed { i, (cx, cy) ->
            if (hypot(x - cx, y - cy) <= hitRadius) return i + 1
        }
        return NONE
    }

    override fun onDraw(canvas: Canvas) {
        val r = rect ?: return
        val corner = context.dp(16).toFloat()
        canvas.drawRoundRect(r, corner, corner, fill)
        canvas.drawRoundRect(r, corner, corner, stroke)
        for ((cx, cy) in handleCenters(r)) {
            canvas.drawCircle(cx, cy, radius, handleFill)
            canvas.drawCircle(cx, cy, radius, handleStroke)
        }
    }
}
