package com.hassan.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class HomeGestures(
    context: Context,
    private val host: Host,
    private val emptyAt: (MotionEvent) -> Boolean,
) : RecyclerView.SimpleOnItemTouchListener() {

    interface Host {
        fun onSwipeUp()
        fun onSwipeDown()
        fun onDoubleTapEmpty()
        fun onLongPressEmpty()
    }

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            if (abs(dy) > abs(dx) * 1.4f && abs(vy) > 900f) {
                if (dy < 0) host.onSwipeUp() else host.onSwipeDown()
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

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return false
    }
}
