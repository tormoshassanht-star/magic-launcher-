package com.hassan.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs

class DrawerSheet(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs), NestedScrollingParent3 {

    var onSlide: ((Float) -> Unit)? = null
    var onOpened: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var headerHeight: () -> Int = { 0 }

    private val helper = NestedScrollingParentHelper(this)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var animator: ValueAnimator? = null
    private var settling = false
    private var dragStartedOpen = false
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var headerTouch = false
    private var velocity: VelocityTracker? = null
    private var initialized = false

    init {
        isClickable = true
        isFocusable = true
    }

    val isShowing: Boolean get() = height > 0 && translationY < height - 1f
    val isOpen: Boolean get() = height > 0 && translationY <= 0.5f
    val fraction: Float get() = if (height == 0) 0f else (1f - translationY / height).coerceIn(0f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) {
            initialized = true
            translationY = h.toFloat()
        } else if (oldh > 0 && translationY >= oldh - 1f) {
            translationY = h.toFloat()
        }
    }

    private fun setPosition(ty: Float) {
        translationY = ty.coerceIn(0f, height.toFloat())
        onSlide?.invoke(fraction)
    }

    fun beginDrag() {
        animator?.cancel()
        animator = null
        settling = false
        dragStartedOpen = isOpen
    }

    fun dragBy(dy: Float) = setPosition(translationY + dy)

    fun settle(velocityY: Float) {
        val open = when {
            velocityY < -300f -> true
            velocityY > 300f -> false
            dragStartedOpen -> fraction > 0.85f
            else -> fraction > 0.1f
        }
        animateTo(open)
    }

    fun open() {
        if (isOpen && animator == null) {
            onOpened?.invoke()
            return
        }
        dragStartedOpen = false
        animateTo(true)
    }

    fun close() {
        if (!isShowing && animator == null) {
            onClosed?.invoke()
            return
        }
        animateTo(false)
    }

    private fun animateTo(open: Boolean) {
        animator?.cancel()
        settling = true
        val target = if (open) 0f else height.toFloat()
        val a = ValueAnimator.ofFloat(translationY, target).apply {
            duration = (140 + 140 * abs(translationY - target) / height.coerceAtLeast(1)).toLong()
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { setPosition(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    settling = false
                    setPosition(target)
                    if (open) onOpened?.invoke() else onClosed?.invoke()
                }
            })
        }
        animator = a
        a.start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                lastY = ev.y
                dragging = false
                headerTouch = ev.y < headerHeight()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (headerTouch && !dragging && abs(ev.y - downY) > slop) {
                    dragging = true
                    beginDrag()
                    lastY = ev.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragBy(event.y - lastY)
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    settle(velocity?.yVelocity ?: 0f)
                }
                release()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(0f)
                release()
            }
        }
        return dragging || super.onTouchEvent(event)
    }

    private fun release() {
        dragging = false
        headerTouch = false
        velocity?.recycle()
        velocity = null
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        helper.onNestedScrollAccepted(child, target, axes, type)
        if (type == ViewCompat.TYPE_TOUCH) beginDrag()
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (type != ViewCompat.TYPE_TOUCH) return
        if (dy < 0 && !target.canScrollVertically(-1)) {
            setPosition(translationY - dy)
            consumed[1] = dy
        } else if (dy > 0 && translationY > 0f) {
            val before = translationY
            setPosition(translationY - dy)
            consumed[1] = (before - translationY).toInt()
        }
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray) = Unit

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int) = Unit

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        if (translationY > 0f) {
            settle(-velocityY)
            return true
        }
        return false
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        helper.onStopNestedScroll(target, type)
        if (type == ViewCompat.TYPE_TOUCH && translationY > 0f && !settling) settle(0f)
    }

    override fun getNestedScrollAxes(): Int = helper.nestedScrollAxes
}
