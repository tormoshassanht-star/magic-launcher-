package com.hassan.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView

class LauncherWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LauncherWidgetView(context)
}

class LauncherWidgetView(context: Context) : AppWidgetHostView(context) {

    private var lastError: String? = null

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        lastError = null
        if (remoteViews != null) {
            try {
                remoteViews.apply(context, this)
            } catch (e: Throwable) {
                lastError = e.toString()
                Log.w("MagicLauncher", "Widget $appWidgetId failed to apply", e)
            }
        }
        super.updateAppWidget(remoteViews)
    }

    override fun getErrorView(): View {
        val label = try {
            appWidgetInfo?.loadLabel(context.packageManager)
        } catch (e: Exception) {
            null
        } ?: "Widget"
        return TextView(context).apply {
            text = "$label couldn't load.\n${lastError ?: "No details from Android."}\n\nTap to retry."
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            background = GradientDrawable().apply {
                setColor(0x66000000)
                cornerRadius = context.dp(16).toFloat()
            }
            setOnClickListener {
                val info = appWidgetInfo ?: return@setOnClickListener
                try {
                    setAppWidget(appWidgetId, info)
                    updateAppWidget(null)
                } catch (e: Exception) {
                }
            }
        }
    }
}
