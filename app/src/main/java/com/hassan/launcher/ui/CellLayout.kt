package com.hassan.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

class CellLayout(context: Context) : ViewGroup(context) {

    var cols = 4
    var rows = 6
    var gestures: HomeGestures? = null
    var previewRect: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    class LayoutParams(var col: Int, var row: Int, var spanX: Int, var spanY: Int) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    val cellW: Float get() = (width - paddingLeft - paddingRight) / cols.toFloat()
    val cellH: Float get() = (height - paddingTop - paddingBottom) / rows.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / cols
        val ch = (h - paddingTop - paddingBottom) / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec(cw * lp.spanX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.spanY, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (width - paddingLeft - paddingRight) / cols
        val ch = (height - paddingTop - paddingBottom) / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = paddingLeft + lp.col * cw
            val top = paddingTop + lp.row * ch
            child.layout(left, top, left + cw * lp.spanX, top + ch * lp.spanY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        previewRect?.let {
            val r = context.dp(16).toFloat()
            canvas.drawRoundRect(it, r, r, fill)
            canvas.drawRoundRect(it, r, r, stroke)
        }
    }

    fun childAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (x >= v.left && x < v.right && y >= v.top && y < v.bottom) return v
        }
        return null
    }

    fun cellRect(col: Int, row: Int, spanX: Int, spanY: Int): RectF {
        val cw = cellW
        val ch = cellH
        val inset = context.dp(4).toFloat()
        val left = paddingLeft + col * cw
        val top = paddingTop + row * ch
        return RectF(left + inset, top + inset, left + cw * spanX - inset, top + ch * spanY - inset)
    }

    private var ignoreGesture = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) ignoreGesture = childAt(ev.x, ev.y) is WidgetFrame
        if (!ignoreGesture) gestures?.detector?.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && !ignoreGesture) gestures?.detector?.onTouchEvent(event)
        return true
    }

    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, 1, 1)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?) = LayoutParams(0, 0, 1, 1)
}
