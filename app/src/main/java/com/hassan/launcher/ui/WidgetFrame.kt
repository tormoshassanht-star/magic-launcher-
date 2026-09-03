package com.hassan.launcher.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class WidgetFrame(context: Context) : FrameLayout(context) {

    var onLongPress: (() -> Unit)? = null
    private var pending: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                cancelPending()
                val r = Runnable {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress?.invoke()
                }
                pending = r
                postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) cancelPending()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPending()
        }
        return fired
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) cancelPending()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPending()
        }
        return true
    }

    private fun cancelPending() {
        pending?.let { removeCallbacks(it) }
        pending = null
    }

    override fun onDetachedFromWindow() {
        cancelPending()
        super.onDetachedFromWindow()
    }
}
