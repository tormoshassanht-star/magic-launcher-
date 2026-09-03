package com.hassan.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DefaultLauncher {
    fun openSettings(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Set Magic Launcher as default")
            .setMessage(
                "On Honor and Huawei phones go to:\n\n" +
                    "Settings › Apps › Apps › ⋮ (top right) › Default apps › Launcher\n\n" +
                    "or Settings › Apps › Default apps › Launcher on newer versions, then choose Magic Launcher.",
            )
            .setNegativeButton("Later", null)
            .setPositiveButton("Open settings") { _, _ ->
                val candidates = listOf(
                    Intent(Settings.ACTION_HOME_SETTINGS),
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    Intent(Settings.ACTION_APPLICATION_SETTINGS),
                )
                for (intent in candidates) {
                    try {
                        context.startActivity(intent)
                        return@setPositiveButton
                    } catch (e: Exception) {
                    }
                }
                Toast.makeText(context, "Open Settings › Apps › Default apps › Launcher", Toast.LENGTH_LONG).show()
            }
            .show()
    }
}
