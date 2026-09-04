package com.hassan.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class HomeGestures(
    context: Context,
    private val host: Host,
    private val emptyAt: (MotionEvent) -> Boolean,
) : RecyclerView.SimpleOnItemTouchListener() {

    interface Host {
        fun onPullStart(): Boolean
        fun onPull(dy: Float)
        fun onPullEnd(velocityY: Float)
        fun onSwipeDown(fromRight: Boolean)
        fun onGestureEnd()
        fun onDoubleTapEmpty()
        fun onLongPressEmpty()
        fun onPinchIn()
    }

    private val halfScreen = context.resources.displayMetrics.widthPixels / 2f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val trigger = context.dp(56).toFloat()
    private var fired = false
    private var pinchTotal = 1f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var velocity: VelocityTracker? = null

    var pulling = false
        private set

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || fired) return false
            val ty = e2.y - e1.y
            val tx = e2.x - e1.x
            if (ty > trigger && ty > abs(tx) * 1.4f) {
                fired = true
                host.onSwipeDown(e1.rawX > halfScreen)
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || fired) return false
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            if (dy > 0 && dy > abs(dx) * 1.4f && vy > 600f) {
                fired = true
                host.onSwipeDown(e1.rawX > halfScreen)
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                pulling = false
                downX = e.x
                downY = e.y
                lastY = e.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (e.pointerCount > 1) fired = true
                if (!pulling && !fired && e.pointerCount == 1) {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (dy < -slop && abs(dy) > abs(dx) * 1.3f) {
                        if (host.onPullStart()) {
                            pulling = true
                            fired = true
                            lastY = e.y
                            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                            detector.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                }
                if (pulling) {
                    host.onPull(e.y - lastY)
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                if (pulling) {
                    velocity?.computeCurrentVelocity(1000)
                    host.onPullEnd(velocity?.yVelocity ?: 0f)
                }
                pulling = false
                velocity?.recycle()
                velocity = null
                host.onGestureEnd()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pulling) host.onPullEnd(0f)
                pulling = false
                velocity?.recycle()
                velocity = null
                host.onGestureEnd()
            }
        }
        if (!pulling) {
            detector.onTouchEvent(e)
            scaleDetector.onTouchEvent(e)
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        onTouch(e)
        return pulling
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        onTouch(e)
    }
}
