package com.hassan.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.hassan.launcher.model.AppInfo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

object IconCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile var shape = "rounded"
        private set
    @Volatile var sizePx = 0
        private set

    fun configure(context: Context, newShape: String, scalePercent: Int) {
        val size = (56 * context.resources.displayMetrics.density * scalePercent / 100f).toInt()
        if (newShape != shape || size != sizePx) {
            shape = newShape
            sizePx = size
            cache.evictAll()
        }
    }

    fun preload(context: Context, apps: List<AppInfo>) {
        if (sizePx == 0) return
        for (app in apps) get(context, app)
    }

    fun get(context: Context, app: AppInfo): Bitmap? {
        if (sizePx == 0) return null
        cache.get(app.key)?.let { return it }
        val info = AppRepository.launcherInfo(app.key) ?: return null
        val drawable = try {
            info.getIcon(context.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            return null
        } ?: return null
        val bmp = render(drawable, sizePx)
        cache.put(app.key, bmp)
        return bmp
    }

    private fun render(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            c.clipPath(shapePath(size))
            val inset = size / 4
            val bg = d.background
            val fg = d.foreground
            bg?.setBounds(-inset, -inset, size + inset, size + inset)
            fg?.setBounds(-inset, -inset, size + inset, size + inset)
            bg?.draw(c)
            fg?.draw(c)
        } else {
            val pad = (size * 0.04f).toInt()
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(c)
        }
        return bmp
    }

    private fun shapePath(size: Int): Path {
        val s = size.toFloat()
        val p = Path()
        when (shape) {
            "circle" -> p.addOval(RectF(0f, 0f, s, s), Path.Direction.CW)
            "square" -> p.addRoundRect(RectF(0f, 0f, s, s), s * 0.08f, s * 0.08f, Path.Direction.CW)
            "squircle" -> {
                val r = s / 2f
                val n = 4.0
                var first = true
                var t = 0.0
                while (t <= Math.PI * 2 + 0.01) {
                    val ct = cos(t)
                    val st = sin(t)
                    val x = r + r * sign(ct) * abs(ct).pow(2.0 / n)
                    val y = r + r * sign(st) * abs(st).pow(2.0 / n)
                    if (first) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                    first = false
                    t += 0.02
                }
                p.close()
            }
            else -> p.addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW)
        }
        return p
    }
}
