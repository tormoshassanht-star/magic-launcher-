package com.hassan.launcher.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationBadgeService : NotificationListenerService() {

    companion object {
        private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val counts: StateFlow<Map<String, Int>> = _counts

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    override fun onListenerConnected() = recompute()

    override fun onListenerDisconnected() {
        _counts.value = emptyMap()
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
        for (sbn in active) {
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) continue
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            val add = if (n.number > 0) n.number else 1
            map[sbn.packageName] = (map[sbn.packageName] ?: 0) + add
        }
        _counts.value = map
    }
}
