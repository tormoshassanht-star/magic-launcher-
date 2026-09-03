package com.hassan.launcher.ui

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

class PopupEntry(val title: String, val icon: Int, val destructive: Boolean = false, val action: () -> Unit)

object AppPopup {
    fun show(anchor: View, title: String, entries: List<PopupEntry>): PopupWindow {
        val ctx = anchor.context
        val dark = ctx.isNightMode()
        val bg = if (dark) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF2F2F2.toInt() else 0xFF1B1B1B.toInt()
        val sub = if (dark) 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt()
        val danger = 0xFFE53935.toInt()
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(bg)
            }
            elevation = ctx.dp(10).toFloat()
            minimumWidth = ctx.dp(210)
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            clipToOutline = true
        }
        container.addView(TextView(ctx).apply {
            this.text = title
            textSize = 12f
            setTextColor(sub)
            maxLines = 1
            setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), ctx.dp(4))
        })

        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.elevation = ctx.dp(10).toFloat()

        for (e in entries) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(18), ctx.dp(11), ctx.dp(20), ctx.dp(11))
                background = ripple.getDrawable(0)
                isClickable = true
                isFocusable = true
            }
            val color = if (e.destructive) danger else text
            row.addView(ImageView(ctx).apply {
                setImageResource(e.icon)
                imageTintList = ColorStateList.valueOf(color)
            }, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
            row.addView(TextView(ctx).apply {
                this.text = e.title
                textSize = 15f
                setTextColor(color)
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(16)
            })
            row.setOnClickListener {
                popup.dismiss()
                e.action()
            }
            container.addView(row)
        }
        ripple.recycle()

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = container.measuredWidth
        val h = container.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val root = anchor.rootView
        val margin = ctx.dp(8)
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(margin, (root.width - w - margin).coerceAtLeast(margin))
        var y = loc[1] - h - ctx.dp(10)
        if (y < ctx.dp(48)) y = loc[1] + anchor.height + ctx.dp(10)

        container.alpha = 0f
        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.pivotX = w / 2f
        container.pivotY = if (y < loc[1]) h.toFloat() else 0f
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        container.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        return popup
    }
}
