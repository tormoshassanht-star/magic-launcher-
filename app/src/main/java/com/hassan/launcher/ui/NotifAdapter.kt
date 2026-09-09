package com.hassan.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.R
import com.hassan.launcher.service.NotifItem

class NotifGroup(val packageName: String, val label: String, val items: List<NotifItem>) {
    val time get() = items.first().time
    val clearable get() = items.any { it.clearable }
    val keys get() = items.filter { it.clearable }.map { it.key }
}

class NotifAdapter(
    private val context: Context,
    private val onOpen: (NotifItem) -> Unit,
    private val onLongPress: (NotifGroup) -> Unit,
) : RecyclerView.Adapter<NotifAdapter.Holder>() {

    private val pm = context.packageManager
    private val icons = HashMap<String, Drawable?>()
    private val labels = HashMap<String, String>()
    private val expanded = HashSet<String>()
    var groups: List<NotifGroup> = emptyList()
        private set

    fun submit(items: List<NotifItem>) {
        val order = LinkedHashMap<String, MutableList<NotifItem>>()
        for (it in items) order.getOrPut(it.packageName) { ArrayList() }.add(it)
        groups = order.map { (pkg, list) -> NotifGroup(pkg, labelFor(pkg), list) }
        expanded.retainAll(groups.map { it.packageName }.toSet())
        notifyDataSetChanged()
    }

    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun iconFor(pkg: String): Drawable? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
    }

    override fun getItemCount() = groups.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_notif_card, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val g = groups[position]
        holder.icon.setImageDrawable(iconFor(g.packageName))
        holder.rows.removeAllViews()
        val open = expanded.contains(g.packageName)
        val shown = if (open) g.items else g.items.take(3)
        shown.forEachIndexed { i, item ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_notif_row, holder.rows, false)
            val title = row.findViewById<TextView>(R.id.title)
            val text = row.findViewById<TextView>(R.id.text)
            val time = row.findViewById<TextView>(R.id.time)
            val avatar = row.findViewById<ImageView>(R.id.avatar)
            val first = i == 0
            title.text = if (first && item.title.isBlank()) g.label else item.title.ifBlank { g.label }
            text.text = item.text
            text.isVisible = item.text.isNotBlank()
            text.maxLines = if (first || open) 4 else 1
            time.text = relative(item.time)
            time.isVisible = first || open
            val avatarDrawable = if (first || open) item.largeIcon?.let { runCatching { it.loadDrawable(context) }.getOrNull() } else null
            if (avatarDrawable != null) {
                avatar.setImageDrawable(avatarDrawable)
                avatar.clipToOutline = true
                avatar.outlineProvider = CircleOutline
                avatar.isVisible = true
            } else {
                avatar.isVisible = false
            }
            if (!first) (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin = context.dp(if (open) 12 else 6)
            row.setOnClickListener { onOpen(item) }
            row.setOnLongClickListener { onLongPress(g); true }
            holder.rows.addView(row)
        }
        if (g.items.size > 3 && !open) {
            val more = TextView(context).apply {
                text = "+${g.items.size - 3} more"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12f
                setPadding(0, context.dp(8), 0, 0)
                setOnClickListener {
                    expanded.add(g.packageName)
                    notifyItemChanged(position)
                }
            }
            holder.rows.addView(more)
        } else if (open && g.items.size > 3) {
            val less = TextView(context).apply {
                text = "Show less"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12f
                setPadding(0, context.dp(8), 0, 0)
                setOnClickListener {
                    expanded.remove(g.packageName)
                    notifyItemChanged(position)
                }
            }
            holder.rows.addView(less)
        }
        holder.itemView.setOnLongClickListener { onLongPress(g); true }
    }

    private fun relative(t: Long): String {
        val diff = System.currentTimeMillis() - t
        return if (diff < DateUtils.MINUTE_IN_MILLIS) "Just now"
        else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val rows: LinearLayout = v.findViewById(R.id.rows)
    }

    private object CircleOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
}
