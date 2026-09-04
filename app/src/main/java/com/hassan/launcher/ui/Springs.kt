package com.hassan.launcher.ui

import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.hassan.launcher.R
import kotlin.math.pow

object Springs {
    // Apple's "response" (seconds to settle) mapped onto a spring stiffness.
    fun stiffness(response: Float): Float = (2 * Math.PI / response).pow(2.0).toFloat()

    fun of(view: View, prop: FloatPropertyCompat<View>, damping: Float = 1f, response: Float = 0.35f): SpringAnimation =
        SpringAnimation(view, prop).setSpring(
            SpringForce().setDampingRatio(damping).setStiffness(stiffness(response)),
        )

    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.25f): Float =
        (overshoot * dimension * constant) / (dimension + constant * overshoot)

    fun scaleTo(view: View, scale: Float, damping: Float = 1f, response: Float = 0.3f) {
        if (reducedMotion(view.context)) {
            view.scaleX = scale
            view.scaleY = scale
            return
        }
        spring(view, DynamicAnimation.SCALE_X, damping, response).animateToFinalPosition(scale)
        spring(view, DynamicAnimation.SCALE_Y, damping, response).animateToFinalPosition(scale)
    }

    fun alphaTo(view: View, alpha: Float, response: Float = 0.25f) {
        if (reducedMotion(view.context)) {
            view.alpha = alpha
            return
        }
        spring(view, DynamicAnimation.ALPHA, 1f, response).animateToFinalPosition(alpha)
    }

    // View.setTag(int, Object) only accepts ids declared in resources; anything else throws.
    private fun spring(view: View, prop: FloatPropertyCompat<View>, damping: Float, response: Float): SpringAnimation {
        val key = when (prop) {
            DynamicAnimation.SCALE_X -> R.id.spring_scale_x
            DynamicAnimation.SCALE_Y -> R.id.spring_scale_y
            else -> R.id.spring_alpha
        }
        val existing = view.getTag(key) as? SpringAnimation
        if (existing != null) {
            existing.spring.dampingRatio = damping
            existing.spring.stiffness = stiffness(response)
            return existing
        }
        val s = of(view, prop, damping, response)
        view.setTag(key, s)
        return s
    }
}

object PressFeedback {
    fun attach(view: View, pressedScale: Float = 0.9f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> Springs.scaleTo(v, pressedScale, 1f, 0.18f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Springs.scaleTo(v, 1f, 0.75f, 0.3f)
            }
            false
        }
    }
}
