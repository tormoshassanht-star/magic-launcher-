package com.hassan.launcher.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotifItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val time: Long,
    val largeIcon: Icon?,
    val contentIntent: PendingIntent?,
    val clearable: Boolean,
)

class NotificationBadgeService : NotificationListenerService() {

    companion object {
        private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val counts: StateFlow<Map<String, Int>> = _counts

        private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
        val items: StateFlow<List<NotifItem>> = _items

        @Volatile var connected = false
        @Volatile private var instance: NotificationBadgeService? = null

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun rebind(context: Context) {
            if (!isEnabled(context) || connected) return
            try {
                requestRebind(android.content.ComponentName(context, NotificationBadgeService::class.java))
            } catch (e: Exception) {
            }
        }

        fun dismiss(keys: List<String>) {
            val svc = instance ?: return
            keys.forEach { runCatching { svc.cancelNotification(it) } }
        }

        fun dismissAll() {
            val svc = instance ?: return
            val keys = _items.value.filter { it.clearable }.map { it.key }
            if (keys.isEmpty()) return
            runCatching { svc.cancelNotifications(keys.toTypedArray()) }
        }
    }

    override fun onListenerConnected() {
        connected = true
        instance = this
        recompute()
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        _counts.value = emptyMap()
        _items.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recompute()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recompute()

    private fun recompute() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            return
        } ?: return
        val map = HashMap<String, Int>()
        val list = ArrayList<NotifItem>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) continue
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            val add = if (n.number > 0) n.number else 1
            map[sbn.packageName] = (map[sbn.packageName] ?: 0) + add
            val (title, text) = extractText(n.extras)
            if (title.isBlank() && text.isBlank()) continue
            list += NotifItem(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title,
                text = text,
                time = sbn.postTime,
                largeIcon = n.getLargeIcon(),
                contentIntent = n.contentIntent,
                clearable = sbn.isClearable,
            )
        }
        _counts.value = map
        _items.value = list.sortedByDescending { it.time }
    }

    private fun extractText(extras: Bundle): Pair<String, String> {
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val text = when {
            !lines.isNullOrEmpty() -> lines.takeLast(3).joinToString("\n")
            else -> (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()
        }?.trim().orEmpty()
        return title to text
    }
}
