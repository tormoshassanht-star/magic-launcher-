package com.hassan.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.min

class DrawerSheet(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs), NestedScrollingParent3 {

    var onSlide: ((Float) -> Unit)? = null
    var onOpened: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var headerHeight: () -> Int = { 0 }

    private val helper = NestedScrollingParentHelper(this)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var settling = false
    private var settleOpen = false
    private var dragStartedOpen = false
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var headerTouch = false
    private var velocity: VelocityTracker? = null
    private var initialized = false

    // Critically damped: a sheet that settles without bouncing past its edge.
    private val spring = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y).apply {
        spring = SpringForce().setDampingRatio(1f).setStiffness(Springs.stiffness(0.32f))
        addUpdateListener { _, _, _ -> onSlide?.invoke(fraction) }
        addEndListener { _, canceled, _, _ ->
            if (canceled) return@addEndListener
            settling = false
            if (settleOpen) {
                translationY = 0f
                onOpened?.invoke()
            } else {
                translationY = height.toFloat()
                onClosed?.invoke()
            }
            onSlide?.invoke(fraction)
        }
    }

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
        val h = height.toFloat()
        translationY = if (ty < 0f) -min(Springs.rubberband(-ty, h), context.dp(48).toFloat()) else min(ty, h)
        onSlide?.invoke(fraction)
    }

    fun beginDrag() {
        spring.cancel()
        settling = false
        dragStartedOpen = isOpen
    }

    fun dragBy(dy: Float) = setPosition(translationY + dy)

    fun settle(velocityY: Float) {
        val h = height.toFloat().coerceAtLeast(1f)
        val projected = translationY + Springs.project(velocityY)
        val projectedFraction = (1f - projected / h).coerceIn(0f, 1f)
        val open = when {
            abs(velocityY) > 300f -> velocityY < 0f
            dragStartedOpen -> projectedFraction > 0.85f
            else -> projectedFraction > 0.1f
        }
        animateTo(open, velocityY)
    }

    fun open() {
        if (isOpen && !settling) {
            onOpened?.invoke()
            return
        }
        dragStartedOpen = false
        animateTo(true, 0f)
    }

    fun close() {
        if (!isShowing && !settling) {
            onClosed?.invoke()
            return
        }
        animateTo(false, 0f)
    }

    private fun animateTo(open: Boolean, velocityY: Float) {
        settleOpen = open
        val target = if (open) 0f else height.toFloat()
        if (Springs.reducedMotion(context)) {
            settling = false
            translationY = target
            onSlide?.invoke(fraction)
            if (open) onOpened?.invoke() else onClosed?.invoke()
            return
        }
        settling = true
        spring.setStartVelocity(velocityY)
        spring.animateToFinalPosition(target)
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
