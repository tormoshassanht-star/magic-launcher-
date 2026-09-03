package com.hassan.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class HomeGestures(
    context: Context,
    private val host: Host,
    private val emptyAt: (MotionEvent) -> Boolean,
) : RecyclerView.SimpleOnItemTouchListener() {

    interface Host {
        fun onSwipeUp()
        fun onSwipeDown(fromRight: Boolean)
        fun onDoubleTapEmpty()
        fun onLongPressEmpty()
        fun onPinchIn()
    }

    private val halfScreen = context.resources.displayMetrics.widthPixels / 2f
    private val trigger = context.dp(56).toFloat()
    private var fired = false
    private var pinchTotal = 1f

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            fired = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || fired) return false
            val ty = e2.y - e1.y
            val tx = e2.x - e1.x
            if (abs(ty) > trigger && abs(ty) > abs(tx) * 1.4f) {
                fired = true
                if (ty < 0) host.onSwipeUp() else host.onSwipeDown(e1.rawX > halfScreen)
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || fired) return false
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            if (abs(dy) > abs(dx) * 1.4f && abs(vy) > 600f) {
                fired = true
                if (dy < 0) host.onSwipeUp() else host.onSwipeDown(e1.rawX > halfScreen)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (emptyAt(e)) host.onDoubleTapEmpty()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (emptyAt(e)) host.onLongPressEmpty()
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchTotal = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchTotal *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (pinchTotal < 0.8f) host.onPinchIn()
        }
    })

    fun onTouch(e: MotionEvent) {
        if (e.pointerCount > 1) fired = true
        detector.onTouchEvent(e)
        scaleDetector.onTouchEvent(e)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        onTouch(e)
        return false
    }
}
