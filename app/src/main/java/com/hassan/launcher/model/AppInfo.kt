package com.hassan.launcher.model

data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val installTime: Long,
    val updateTime: Long,
    val isSystem: Boolean,
) {
    val key: String get() = "$packageName/$activityName"

    val sortLetter: Char
        get() {
            val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (c.isLetter() && c in 'A'..'Z') c else '#'
        }
}
