package com.hassan.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hassan.launcher.R
import com.hassan.launcher.data.Prefs
import com.hassan.launcher.data.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "launcher"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            click("hidden_apps") { startActivity(Intent(requireContext(), HiddenAppsActivity::class.java)) }
            click("accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            click("lock_permission") {
                val ctx = requireContext()
                if (com.hassan.launcher.service.LockAdminReceiver.isActive(ctx)) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Screen lock permission is on")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Turn off") { _, _ -> com.hassan.launcher.service.LockAdminReceiver.remove(ctx) }
                        .show()
                } else {
                    startActivity(
                        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, com.hassan.launcher.service.LockAdminReceiver.component(ctx))
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Used only for double tap to lock the screen."),
                    )
                }
            }
            click("notification_access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            click("notif_hidden") {
                val ctx = requireContext()
                val prefs = Prefs(ctx)
                val pkgs = prefs.notifHidden.toList()
                if (pkgs.isEmpty()) {
                    Toast.makeText(ctx, "Nothing hidden. Long press a notification in the panel to hide its app.", Toast.LENGTH_LONG).show()
                    return@click
                }
                val pm = ctx.packageManager
                val names = pkgs.map { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }.toTypedArray()
                val checked = BooleanArray(pkgs.size) { true }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Hidden from notification panel")
                    .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ -> prefs.notifHidden = pkgs.filterIndexed { i, _ -> checked[i] }.toSet() }
                    .show()
            }
            click("battery") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Keep Magic Launcher running")
                    .setMessage("1. Allow the exception on the next screen.\n2. Then open Settings > Battery > App launch, find Magic Launcher, switch it to Manage manually and turn on all three options.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue") { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(android.net.Uri.parse("package:${requireContext().packageName}")),
                            )
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    .show()
            }
            click("default_launcher") { DefaultLauncher.openSettings(requireContext()) }
            click("wallpaper") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
            }
            click("reset_home") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Reset home layout?")
                    .setMessage("Your home screen pages and dock will be rebuilt with the default apps.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        Prefs(requireContext()).resetLayout()
                        Toast.makeText(requireContext(), "Home layout reset", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            click("check_update") {
                Toast.makeText(requireContext(), "Checking…", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val info = Updater.check()
                    if (info == null) {
                        Toast.makeText(requireContext(), "You have the latest version", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    Prefs(requireContext()).skippedUpdate = ""
                    startActivity(
                        Intent(requireContext(), LauncherActivity::class.java)
                            .putExtra("check_update", true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                    requireActivity().finish()
                }
            }
            click("about") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Magic Launcher")
                    .setMessage(
                        "A clean, fast launcher inspired by Honor's Magic UI.\n\n" +
                            "• Sort the app drawer by name, install date, last update, most used or recently used\n" +
                            "• Newly installed apps get a red dot for 48 hours\n" +
                            "• Multi-select apps to uninstall, hide or add to Home in one go\n" +
                            "• Hidden apps, letter fast-scroll, web and Play Store search\n" +
                            "• Double tap to lock, swipe down for notifications\n" +
                            "• Share your full app list as text",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                action()
                true
            }
        }
    }
}
