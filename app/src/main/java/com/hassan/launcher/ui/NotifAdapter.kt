package com.hassan.launcher.ui

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.R
import com.hassan.launcher.service.NotifItem
import com.hassan.launcher.service.NotificationBadgeService

class NotifGroup(val packageName: String, val label: String, val items: List<NotifItem>) {
    val time get() = items.first().time
    val clearable get() = items.any { it.clearable }
    val keys get() = items.filter { it.clearable }.map { it.key }
    val allKeys get() = items.map { it.key }
}

sealed class NotifRow {
    class Media(val info: MediaInfo) : NotifRow()
    class Header(val title: String, val action: String?, val silent: Boolean) : NotifRow()
    class Card(val group: NotifGroup) : NotifRow()
}

class NotifAdapter(
    private val context: Context,
    private val onOpen: (NotifItem) -> Unit,
    private val onLongPress: (NotifGroup, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val pm = context.packageManager
    private val icons = HashMap<String, Drawable?>()
    private val labels = HashMap<String, String>()
    private val expanded = HashSet<String>()
    private var replying: String? = null
    private var items: List<NotifItem> = emptyList()
    private var media: MediaInfo? = null
    var hidden: Set<String> = emptySet()
    var rows: List<NotifRow> = emptyList()
        private set
    val count get() = items.size
    val isEmpty get() = items.isEmpty() && media == null

    fun submit(list: List<NotifItem>) {
        items = list.filter { it.packageName !in hidden }
        rebuild()
    }

    fun setMedia(info: MediaInfo?) {
        media = info
        rebuild()
    }

    private fun rebuild() {
        val out = ArrayList<NotifRow>()
        media?.let { out += NotifRow.Media(it) }
        val convo = group(items.filter { it.conversation && !it.silent })
        val normal = group(items.filter { !it.conversation && !it.silent })
        val silent = group(items.filter { it.silent })
        if (convo.isNotEmpty()) {
            if (normal.isNotEmpty() || silent.isNotEmpty()) out += NotifRow.Header("Conversations", null, false)
            convo.forEach { out += NotifRow.Card(it) }
        }
        if (normal.isNotEmpty()) {
            if (convo.isNotEmpty() || silent.isNotEmpty()) out += NotifRow.Header("Notifications", null, false)
            normal.forEach { out += NotifRow.Card(it) }
        }
        if (silent.isNotEmpty()) {
            out += NotifRow.Header("Silent", "Clear", true)
            silent.forEach { out += NotifRow.Card(it) }
        }
        rows = out
        expanded.retainAll(rows.filterIsInstance<NotifRow.Card>().map { it.group.packageName }.toSet())
        if (replying != null && items.none { it.key == replying }) replying = null
        notifyDataSetChanged()
    }

    private fun group(list: List<NotifItem>): List<NotifGroup> {
        val order = LinkedHashMap<String, MutableList<NotifItem>>()
        for (it in list) order.getOrPut(it.packageName) { ArrayList() }.add(it)
        return order.map { (pkg, l) -> NotifGroup(pkg, labelFor(pkg), l) }
    }

    fun groupAt(position: Int): NotifGroup? = (rows.getOrNull(position) as? NotifRow.Card)?.group

    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun iconFor(pkg: String): Drawable? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is NotifRow.Media -> 0
        is NotifRow.Header -> 1
        is NotifRow.Card -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> MediaHolder(inf.inflate(R.layout.item_notif_media, parent, false))
            1 -> HeaderHolder(inf.inflate(R.layout.item_notif_header, parent, false))
            else -> Holder(inf.inflate(R.layout.item_notif_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NotifRow.Media -> bindMedia(holder as MediaHolder, row.info)
            is NotifRow.Header -> bindHeader(holder as HeaderHolder, row)
            is NotifRow.Card -> bindCard(holder as Holder, row.group, position)
        }
    }

    private fun bindMedia(h: MediaHolder, info: MediaInfo) {
        h.title.text = info.title
        h.title.isSelected = true
        h.artist.text = info.artist.ifBlank { labelFor(info.packageName) }
        if (info.art != null) h.art.setImageBitmap(info.art) else h.art.setImageDrawable(iconFor(info.packageName))
        h.art.clipToOutline = true
        h.art.outlineProvider = RoundOutline(context.dp(12).toFloat())
        h.playPause.setImageResource(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play)
        h.playPause.setOnClickListener {
            if (info.playing) info.controller.transportControls.pause() else info.controller.transportControls.play()
        }
        h.prev.setOnClickListener { info.controller.transportControls.skipToPrevious() }
        h.next.setOnClickListener { info.controller.transportControls.skipToNext() }
        h.itemView.setOnClickListener {
            pm.getLaunchIntentForPackage(info.packageName)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun bindHeader(h: HeaderHolder, row: NotifRow.Header) {
        h.title.text = row.title
        h.action.text = row.action
        h.action.isVisible = row.action != null
        h.action.setOnClickListener { if (row.silent) NotificationBadgeService.dismissAll(silentOnly = true) }
    }

    private fun bindCard(holder: Holder, g: NotifGroup, position: Int) {
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
            val full = first || open
            title.text = item.title.ifBlank { g.label }
            text.text = item.text
            text.isVisible = item.text.isNotBlank()
            text.maxLines = if (full) 4 else 1
            time.text = relative(item.time)
            time.isVisible = full
            val avatarDrawable = if (full) item.largeIcon?.let { runCatching { it.loadDrawable(context) }.getOrNull() } else null
            if (avatarDrawable != null) {
                avatar.setImageDrawable(avatarDrawable)
                avatar.clipToOutline = true
                avatar.outlineProvider = RoundOutline(context.dp(22).toFloat())
                avatar.isVisible = true
            } else {
                avatar.isVisible = false
            }
            if (!first) (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin = context.dp(if (open) 12 else 6)
            row.setOnClickListener { onOpen(item) }
            row.setOnLongClickListener { onLongPress(g, holder.itemView); true }
            holder.rows.addView(row)
            if (full && item.actions.isNotEmpty()) holder.rows.addView(buildActions(item, position))
            if (replying == item.key) holder.rows.addView(buildReply(item, position))
        }
        if (g.items.size > 3) {
            val toggle = TextView(context).apply {
                text = if (open) "Show less" else "+${g.items.size - 3} more"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12f
                setPadding(0, context.dp(8), 0, 0)
                setOnClickListener {
                    if (open) expanded.remove(g.packageName) else expanded.add(g.packageName)
                    notifyItemChanged(position)
                }
            }
            holder.rows.addView(toggle)
        }
        holder.itemView.setOnLongClickListener { onLongPress(g, holder.itemView); true }
    }

    private fun buildActions(item: NotifItem, position: Int): View {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(10), 0, 0)
        }
        item.actions.take(4).forEach { action ->
            val hasInput = action.remoteInputs?.any { it.allowFreeFormInput } == true
            val pill = TextView(context).apply {
                text = action.title.toString().trim()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                setBackgroundResource(R.drawable.bg_notif_action)
                setPadding(context.dp(14), context.dp(7), context.dp(14), context.dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = context.dp(8)
                }
                setOnClickListener {
                    if (hasInput) {
                        replying = if (replying == item.key) null else item.key
                        notifyItemChanged(position)
                    } else {
                        runCatching { action.actionIntent.send() }
                    }
                }
            }
            strip.addView(pill)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun buildReply(item: NotifItem, position: Int): View {
        val action = item.actions.first { it.remoteInputs?.isNotEmpty() == true }
        val input = action.remoteInputs!!.first { it.allowFreeFormInput }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_notif_action)
            setPadding(context.dp(14), 0, context.dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)).apply { topMargin = context.dp(10) }
        }
        val edit = EditText(context).apply {
            hint = input.label?.toString() ?: "Reply"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            background = null
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val send = ImageView(context).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(context.dp(9), context.dp(9), context.dp(9), context.dp(9))
            layoutParams = LinearLayout.LayoutParams(context.dp(38), context.dp(38))
            setBackgroundResource(android.R.color.transparent)
        }
        val submit = {
            val txt = edit.text.toString().trim()
            if (txt.isNotEmpty()) {
                val intent = Intent()
                val results = Bundle().apply { putCharSequence(input.resultKey, txt) }
                RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                runCatching { action.actionIntent.send(context, 0, intent) }
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken, 0)
                replying = null
                notifyItemChanged(position)
            }
        }
        send.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        box.addView(edit)
        box.addView(send)
        edit.post {
            edit.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        return box
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

    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
        val action: TextView = v.findViewById(R.id.sectionAction)
    }

    class MediaHolder(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.art)
        val title: TextView = v.findViewById(R.id.mediaTitle)
        val artist: TextView = v.findViewById(R.id.mediaArtist)
        val prev: ImageView = v.findViewById(R.id.prev)
        val playPause: ImageView = v.findViewById(R.id.playPause)
        val next: ImageView = v.findViewById(R.id.next)
    }

    private class RoundOutline(val radius: Float) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}
