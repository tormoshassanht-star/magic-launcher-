package com.hassan.launcher.ui

import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.databinding.ItemHomeAppBinding

class HomeItemAdapter(
    val items: MutableList<HomeItem>,
    private val cellHeight: Int,
    private val showLabels: Boolean,
    private val onClick: (HomeItem, View) -> Unit,
    private val onLongClick: (HomeItem, View) -> Unit,
    private val badgeFor: (HomeItem) -> Int = { 0 },
) : RecyclerView.Adapter<HomeItemAdapter.VH>() {

    class VH(val b: ItemHomeAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHomeAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.layoutParams = b.root.layoutParams.apply { height = cellHeight }
        val s = IconCache.sizePx
        b.icon.layoutParams = b.icon.layoutParams.apply { width = s; height = s }
        b.label.isVisible = showLabels
        if (!showLabels) {
            b.root.gravity = Gravity.CENTER
            b.root.setPadding(0, 0, 0, 0)
        }
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.b.root.alpha = 1f
        holder.b.root.scaleX = 1f
        holder.b.root.scaleY = 1f
        when (item) {
            is HomeItem.App -> holder.b.icon.setImageBitmap(IconCache.get(ctx, item.app))
            is HomeItem.Folder -> holder.b.icon.setImageBitmap(FolderIcons.render(ctx, item.apps, IconCache.sizePx))
            is HomeItem.Widget -> holder.b.icon.setImageDrawable(null)
        }
        holder.b.label.text = item.label
        Badges.apply(holder.b.countBadge, badgeFor(item))
        holder.b.root.setOnClickListener { onClick(item, holder.b.icon) }
        holder.b.root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongClick(item, holder.b.icon)
            true
        }
    }
}
