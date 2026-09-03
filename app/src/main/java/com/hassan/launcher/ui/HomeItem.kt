package com.hassan.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.model.AppInfo
import kotlin.math.min

sealed class HomeItem {
    abstract val key: String
    abstract val label: String

    data class App(val app: AppInfo) : HomeItem() {
        override val key get() = app.key
        override val label get() = app.label
    }

    data class Folder(val id: String, val name: String, val apps: List<AppInfo>) : HomeItem() {
        override val key get() = "folder:$id"
        override val label get() = name
    }

    data class Widget(val id: Int, val info: AppWidgetProviderInfo?, val title: String) : HomeItem() {
        override val key get() = "widget:$id"
        override val label get() = title
    }

    companion object {
        const val FOLDER_PREFIX = "folder:"
        const val WIDGET_PREFIX = "widget:"
        fun isFolderKey(key: String) = key.startsWith(FOLDER_PREFIX)
        fun folderId(key: String) = key.removePrefix(FOLDER_PREFIX)
        fun isWidgetKey(key: String) = key.startsWith(WIDGET_PREFIX)
        fun widgetId(key: String) = key.removePrefix(WIDGET_PREFIX).toIntOrNull() ?: -1
    }
}

sealed class DragSource {
    data class Page(val index: Int) : DragSource()
    object Dock : DragSource()
    data class Folder(val id: String) : DragSource()
    object Drawer : DragSource()
}

class DragState(val item: HomeItem, val source: DragSource, val ghost: View, val spanX: Int, val spanY: Int) {
    var dropped = false
}

class LiftShadow(view: View, private val scale: Float) : View.DragShadowBuilder(view) {
    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        outShadowSize.set(w, h)
        outShadowTouchPoint.set(w / 2, h / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.scale(scale, scale)
        view.draw(canvas)
    }
}

object FolderIcons {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(context: Context, apps: List<AppInfo>, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        paint.color = 0x73FFFFFF
        c.drawRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, paint)
        val n = min(apps.size, 9)
        val cols = if (n > 4) 3 else 2
        val pad = s * 0.11f
        val cell = (s - 2 * pad) / cols
        val icon = cell * 0.82f
        for (i in 0 until n) {
            val bitmap = IconCache.get(context, apps[i]) ?: continue
            val col = i % cols
            val row = i / cols
            val left = pad + col * cell + (cell - icon) / 2
            val top = pad + row * cell + (cell - icon) / 2
            c.drawBitmap(bitmap, null, RectF(left, top, left + icon, top + icon), paint)
        }
        return bmp
    }
}
