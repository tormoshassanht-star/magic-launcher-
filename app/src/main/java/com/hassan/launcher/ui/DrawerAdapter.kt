package com.hassan.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.R
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.databinding.ItemDrawerActionBinding
import com.hassan.launcher.databinding.ItemDrawerAppBinding
import com.hassan.launcher.databinding.ItemDrawerHeaderBinding
import com.hassan.launcher.model.AppInfo

class DrawerState {
    var textColor = Color.BLACK
    var subColor = Color.GRAY
    var showNewBadge = true
    var selectionMode = false
    val selected = LinkedHashSet<String>()
    var onClick: (AppInfo, View) -> Unit = { _, _ -> }
    var onLongClick: (AppInfo, View) -> Unit = { _, _ -> }
    var onGroupDrag: (AppInfo, View) -> Unit = { _, _ -> }
    var onSelectionChanged: () -> Unit = {}
}

class DrawerAdapter(private val state: DrawerState, private val cellHeight: Int = 0) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<DrawerItem> = emptyList()
        private set

    private val newWindowMs = 48L * 60 * 60 * 1000

    fun submit(list: List<DrawerItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun toggle(app: AppInfo) {
        if (!state.selected.remove(app.key)) state.selected.add(app.key)
        val idx = items.indexOfFirst { it is DrawerItem.App && it.app.key == app.key }
        if (idx >= 0) notifyItemChanged(idx)
        state.onSelectionChanged()
    }

    fun firstApp(): AppInfo? = (items.firstOrNull { it is DrawerItem.App } as? DrawerItem.App)?.app

    fun isApp(position: Int) = items[position] is DrawerItem.App

    fun positionForLetter(c: Char): Int = items.indexOfFirst { it is DrawerItem.App && it.app.sortLetter == c }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is DrawerItem.Header -> 0
        is DrawerItem.App -> 1
        is DrawerItem.Action -> 2
    }

    class HeaderVH(val b: ItemDrawerHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class AppVH(val b: ItemDrawerAppBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemDrawerActionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemDrawerHeaderBinding.inflate(inf, parent, false))
            1 -> AppVH(ItemDrawerAppBinding.inflate(inf, parent, false)).also { vh ->
                var s = IconCache.sizePx
                if (cellHeight > 0) {
                    val ctx = parent.context
                    vh.b.root.layoutParams = vh.b.root.layoutParams.apply { height = cellHeight }
                    vh.b.root.setPadding(0, ctx.dp(2), 0, ctx.dp(2))
                    s = minOf(s, cellHeight - ctx.dp(36)).coerceAtLeast(ctx.dp(28))
                }
                vh.b.icon.layoutParams = vh.b.icon.layoutParams.apply { width = s; height = s }
            }
            else -> ActionVH(ItemDrawerActionBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DrawerItem.Header -> {
                val h = holder as HeaderVH
                h.b.headerText.text = item.title
                h.b.headerText.setTextColor(state.subColor)
            }
            is DrawerItem.App -> bindApp(holder as AppVH, item)
            is DrawerItem.Action -> {
                val a = holder as ActionVH
                a.b.actionText.text = item.title
                a.b.actionText.setTextColor(state.textColor)
                a.b.actionIcon.setImageResource(item.icon)
                a.b.actionIcon.imageTintList = ColorStateList.valueOf(state.subColor)
                a.b.root.setOnClickListener { item.run() }
            }
        }
    }

    private fun bindApp(vh: AppVH, item: DrawerItem.App) {
        val app = item.app
        val ctx = vh.itemView.context
        vh.b.icon.setImageBitmap(IconCache.get(ctx, app))
        vh.b.label.text = app.label
        vh.b.label.setTextColor(state.textColor)
        vh.b.sub.text = item.sub
        vh.b.sub.isVisible = item.sub != null && cellHeight == 0
        vh.b.sub.setTextColor(state.subColor)
        vh.b.badge.isVisible = state.showNewBadge && System.currentTimeMillis() - app.installTime < newWindowMs
        val isSelected = state.selectionMode && app.key in state.selected
        vh.b.check.isVisible = isSelected
        vh.b.check.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent))
        vh.b.icon.alpha = if (state.selectionMode && !isSelected) 0.55f else 1f
        vh.b.root.setOnClickListener {
            if (state.selectionMode) toggle(app) else state.onClick(app, vh.b.icon)
        }
        vh.b.root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (state.selectionMode) {
                if (app.key !in state.selected) toggle(app)
                state.onGroupDrag(app, vh.b.icon)
            } else state.onLongClick(app, vh.b.icon)
            true
        }
    }
}
