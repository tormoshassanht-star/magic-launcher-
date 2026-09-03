package com.hassan.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import com.hassan.launcher.model.AppInfo

object DefaultLayout {
    fun build(context: Context, apps: List<AppInfo>): Pair<MutableList<MutableList<String>>, MutableList<String>> {
        val pm = context.packageManager

        fun resolve(intent: Intent): AppInfo? {
            val ri = try {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (e: Exception) {
                null
            } ?: return null
            val pkg = ri.activityInfo?.packageName ?: return null
            return apps.firstOrNull { it.packageName == pkg }
        }

        fun category(c: String) = resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, c))

        val dockApps = listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            category(Intent.CATEGORY_APP_MESSAGING),
            category(Intent.CATEGORY_APP_BROWSER),
            resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        ).distinctBy { it.key }.take(5)
        val dock = dockApps.map { it.key }.toMutableList()

        val page = listOfNotNull(
            category(Intent.CATEGORY_APP_GALLERY),
            category(Intent.CATEGORY_APP_CONTACTS),
            resolve(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            category(Intent.CATEGORY_APP_CALENDAR),
            category(Intent.CATEGORY_APP_EMAIL),
            category(Intent.CATEGORY_APP_MAPS),
            category(Intent.CATEGORY_APP_MUSIC),
            category(Intent.CATEGORY_APP_CALCULATOR),
            category(Intent.CATEGORY_APP_MARKET),
            category("android.intent.category.APP_FILES"),
            category("android.intent.category.APP_WEATHER"),
            apps.firstOrNull { it.packageName == "com.android.settings" },
        ).distinctBy { it.key }.filter { it.key !in dock }.map { it.key }.toMutableList()

        return Pair(mutableListOf(page), dock)
    }
}
