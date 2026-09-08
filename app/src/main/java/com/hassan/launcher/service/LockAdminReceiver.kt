package com.hassan.launcher.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class LockAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, LockAdminReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))

        fun lock(context: Context): Boolean {
            if (!isActive(context)) return false
            return try {
                context.getSystemService(DevicePolicyManager::class.java).lockNow()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun remove(context: Context) {
            try {
                context.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(component(context))
            } catch (e: Exception) {
            }
        }
    }
}
