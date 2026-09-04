package com.hassan.launcher.ui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hassan.launcher.R
import com.hassan.launcher.data.Prefs
import com.hassan.launcher.model.AppInfo

sealed class SearchRow {
    data class Header(val title: String) : SearchRow()
    data class App(val app: AppInfo) : SearchRow()
    data class Setting(val label: String, val intent: Intent) : SearchRow()
    data class Contact(val id: Long, val lookupKey: String, val name: String, val phone: String?) : SearchRow()
    data class Media(val uri: Uri, val name: String, val kind: String, val mime: String?) : SearchRow()
    data class Action(val label: String, val icon: Int, val run: () -> Unit) : SearchRow()
    data class Permission(val label: String, val permissions: Array<String>) : SearchRow()
}

object UniversalSearch {

    private class SettingEntry(val label: String, val keywords: String, val intent: Intent)

    private fun settingsIndex(): List<SettingEntry> = listOf(
        SettingEntry("Wi-Fi", "wifi wireless network internet", Intent(Settings.ACTION_WIFI_SETTINGS)),
        SettingEntry("Bluetooth", "bluetooth pair headphones", Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
        SettingEntry("Mobile network", "mobile data sim roaming 4g 5g network", Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)),
        SettingEntry("Data usage", "data usage limit", Intent(Settings.ACTION_DATA_USAGE_SETTINGS)),
        SettingEntry("Hotspot and tethering", "hotspot tethering share internet", Intent("android.settings.TETHER_SETTINGS")),
        SettingEntry("Airplane mode", "airplane flight mode", Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
        SettingEntry("Display", "display brightness screen dark mode font size sleep timeout", Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        SettingEntry("Sound and vibration", "sound volume ringtone vibration silent", Intent(Settings.ACTION_SOUND_SETTINGS)),
        SettingEntry("Do not disturb", "do not disturb dnd quiet focus", Intent("android.settings.ZEN_MODE_SETTINGS")),
        SettingEntry("Notifications", "notifications alerts badges", Intent("android.settings.NOTIFICATION_SETTINGS")),
        SettingEntry("Battery", "battery power saver charging", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        SettingEntry("Storage", "storage space memory clean", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
        SettingEntry("Apps", "apps applications manage permissions uninstall", Intent(Settings.ACTION_APPLICATION_SETTINGS)),
        SettingEntry("Default apps", "default apps launcher browser home", Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
        SettingEntry("Security", "security lock screen password pin fingerprint face unlock", Intent(Settings.ACTION_SECURITY_SETTINGS)),
        SettingEntry("Privacy", "privacy permissions", Intent("android.settings.PRIVACY_SETTINGS")),
        SettingEntry("Location", "location gps", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        SettingEntry("Accounts and sync", "accounts sync google account", Intent(Settings.ACTION_SYNC_SETTINGS)),
        SettingEntry("Language", "language region locale", Intent(Settings.ACTION_LOCALE_SETTINGS)),
        SettingEntry("Keyboard", "keyboard input method typing", Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),
        SettingEntry("Date and time", "date time zone clock", Intent(Settings.ACTION_DATE_SETTINGS)),
        SettingEntry("Accessibility", "accessibility", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        SettingEntry("About phone", "about phone device info version build model", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        SettingEntry("System update", "system update software version", Intent("android.settings.SYSTEM_UPDATE_SETTINGS")),
        SettingEntry("Developer options", "developer options usb debugging", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
        SettingEntry("Wallpaper", "wallpaper background theme", Intent(Intent.ACTION_SET_WALLPAPER)),
        SettingEntry("NFC", "nfc contactless", Intent(Settings.ACTION_NFC_SETTINGS)),
        SettingEntry("Cast", "cast screen mirror wireless display", Intent(Settings.ACTION_CAST_SETTINGS)),
        SettingEntry("VPN", "vpn", Intent(Settings.ACTION_VPN_SETTINGS)),
        SettingEntry("All settings", "settings", Intent(Settings.ACTION_SETTINGS)),
    )

    fun hasContacts(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMedia(context: Context): Boolean =
        mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun recent(apps: List<AppInfo>, prefs: Prefs): List<SearchRow> {
        val used = apps.filter { prefs.lastUsed(it.key) > 0 }.sortedByDescending { prefs.lastUsed(it.key) }.take(8)
        if (used.isEmpty()) return emptyList()
        return listOf(SearchRow.Header("Recent")) + used.map { SearchRow.App(it) }
    }

    fun search(
        context: Context,
        query: String,
        apps: List<AppInfo>,
        prefs: Prefs,
        onWeb: (String) -> Unit,
        onStore: (String) -> Unit,
    ): List<SearchRow> {
        val q = query.trim()
        if (q.isEmpty()) return recent(apps, prefs)
        val ql = q.lowercase()
        val out = ArrayList<SearchRow>()
        val hidden = prefs.hidden

        val matchedApps = apps.filter { it.key !in hidden && (it.label.lowercase().contains(ql) || it.packageName.lowercase().contains(ql)) }
            .sortedWith(compareBy<AppInfo> { !it.label.lowercase().startsWith(ql) }.thenBy { it.label.lowercase() })
            .take(8)
        if (matchedApps.isNotEmpty()) {
            out += SearchRow.Header("Apps")
            matchedApps.mapTo(out) { SearchRow.App(it) }
        }

        val pm = context.packageManager
        val settings = settingsIndex().filter { e ->
            (e.label.lowercase().contains(ql) || e.keywords.split(' ').any { it.startsWith(ql) }) &&
                pm.resolveActivity(e.intent, 0) != null
        }.take(6)
        if (settings.isNotEmpty()) {
            out += SearchRow.Header("Settings")
            settings.mapTo(out) { SearchRow.Setting(it.label, it.intent) }
        }

        if (hasContacts(context)) {
            val contacts = searchContacts(context.contentResolver, q)
            if (contacts.isNotEmpty()) {
                out += SearchRow.Header("Contacts")
                out += contacts
            }
        } else if (ql.length >= 2) {
            out += SearchRow.Header("Contacts")
            out += SearchRow.Permission("Allow contact search", arrayOf(Manifest.permission.READ_CONTACTS))
        }

        if (hasMedia(context)) {
            val media = searchMedia(context.contentResolver, q)
            if (media.isNotEmpty()) {
                out += SearchRow.Header("Photos, videos and music")
                out += media
            }
        } else if (ql.length >= 2) {
            out += SearchRow.Header("Photos, videos and music")
            out += SearchRow.Permission("Allow photo and media search", mediaPermissions())
        }

        out += SearchRow.Header("Web")
        out += SearchRow.Action("Search Google for \"$q\"", R.drawable.ic_search) { onWeb(q) }
        out += SearchRow.Action("Search Play Store for \"$q\"", R.drawable.ic_store) { onStore(q) }
        return out
    }

    private fun searchContacts(cr: ContentResolver, q: String): List<SearchRow> {
        val out = ArrayList<SearchRow>()
        try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
            cr.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, ContactsContract.Contacts.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 5) {
                    val id = c.getLong(0)
                    val lookup = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    var phone: String? = null
                    if (c.getInt(3) > 0) {
                        cr.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(id.toString()), null,
                        )?.use { p -> if (p.moveToFirst()) phone = p.getString(0) }
                    }
                    out += SearchRow.Contact(id, lookup, name, phone)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun searchMedia(cr: ContentResolver, q: String): List<SearchRow> {
        val out = ArrayList<SearchRow>()
        out += queryMedia(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, q, "image", 6)
        out += queryMedia(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, q, "video", 3)
        out += queryMedia(cr, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, q, "audio", 3)
        return out
    }

    private fun queryMedia(cr: ContentResolver, base: Uri, q: String, kind: String, limit: Int): List<SearchRow> {
        val out = ArrayList<SearchRow>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$q%")
        try {
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                val b = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                cr.query(base, projection, b, null)
            } else {
                cr.query(base, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit")
            }
            cursor?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    out += SearchRow.Media(ContentUris.withAppendedId(base, id), name, kind, c.getString(2))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
