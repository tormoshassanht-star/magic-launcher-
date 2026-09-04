package com.hassan.launcher.ui

import android.widget.TextView
import androidx.core.view.isVisible

object Badges {
    fun apply(view: TextView, count: Int) {
        if (count <= 0) {
            view.isVisible = false
            return
        }
        view.text = if (count > 99) "99+" else count.toString()
        view.isVisible = true
    }
}
