package com.hassan.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.R
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.databinding.ItemDrawerActionBinding
import com.hassan.launcher.databinding.ItemDrawerHeaderBinding
import com.hassan.launcher.databinding.ItemSearchRowBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchAdapter(
    private val scope: CoroutineScope,
    private val state: DrawerState,
    private val onRow: (SearchRow, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<SearchRow> = emptyList()
        private set

    fun submit(list: List<SearchRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is SearchRow.Header -> 0
        is SearchRow.Setting, is SearchRow.Action, is SearchRow.Permission -> 1
        else -> 2
    }

    class HeaderVH(val b: ItemDrawerHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemDrawerActionBinding) : RecyclerView.ViewHolder(b.root)
    class RowVH(val b: ItemSearchRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemDrawerHeaderBinding.inflate(inf, parent, false))
            1 -> ActionVH(ItemDrawerActionBinding.inflate(inf, parent, false))
            else -> RowVH(ItemSearchRowBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val ctx = holder.itemView.context
        when (holder) {
            is HeaderVH -> {
                holder.b.headerText.text = (row as SearchRow.Header).title
                holder.b.headerText.setTextColor(state.subColor)
            }
            is ActionVH -> {
                val (label, icon) = when (row) {
                    is SearchRow.Setting -> row.label to R.drawable.ic_settings
                    is SearchRow.Action -> row.label to row.icon
                    is SearchRow.Permission -> row.label to R.drawable.ic_info
                    else -> "" to R.drawable.ic_info
                }
                holder.b.actionText.text = label
                holder.b.actionText.setTextColor(state.textColor)
                holder.b.actionIcon.setImageResource(icon)
                holder.b.actionIcon.imageTintList = ColorStateList.valueOf(state.subColor)
                holder.b.root.setOnClickListener { onRow(row, it) }
            }
            is RowVH -> {
                val b = holder.b
                b.title.setTextColor(state.textColor)
                b.subtitle.setTextColor(state.subColor)
                b.thumb.imageTintList = null
                b.thumb.tag = null
                when (row) {
                    is SearchRow.App -> {
                        b.thumb.setImageBitmap(IconCache.get(ctx, row.app))
                        b.title.text = row.app.label
                        b.subtitle.isVisible = false
                    }
                    is SearchRow.Contact -> {
                        b.thumb.setImageBitmap(letterAvatar(ctx, row.name))
                        b.title.text = row.name
                        b.subtitle.text = row.phone ?: "Contact"
                        b.subtitle.isVisible = true
                    }
                    is SearchRow.Media -> {
                        b.title.text = row.name
                        b.subtitle.text = when (row.kind) {
                            "image" -> "Photo"
                            "video" -> "Video"
                            else -> "Music"
                        }
                        b.subtitle.isVisible = true
                        b.thumb.setImageResource(R.drawable.ic_wallpaper)
                        b.thumb.imageTintList = ColorStateList.valueOf(state.subColor)
                        b.thumb.tag = row.uri
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) { loadThumb(ctx, row.uri) }
                            if (bmp != null && b.thumb.tag == row.uri) {
                                b.thumb.imageTintList = null
                                b.thumb.setImageBitmap(bmp)
                            }
                        }
                    }
                    else -> Unit
                }
                b.root.setOnClickListener { onRow(row, b.thumb) }
            }
        }
    }

    private fun loadThumb(ctx: android.content.Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(uri, Size(128, 128), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                ctx.contentResolver, android.content.ContentUris.parseId(uri),
                MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun letterAvatar(ctx: android.content.Context, name: String): Bitmap {
        val size = ctx.dp(44)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(), 0xFF039BE5.toInt(), 0xFFD81B60.toInt())
        paint.color = colors[(name.hashCode() and 0x7fffffff) % colors.size]
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = size * 0.45f
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        c.drawText(name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?", size / 2f, y, paint)
        return bmp
    }
}
