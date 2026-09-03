package com.hassan.launcher.ui

import android.content.Context
import android.content.res.Configuration

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
