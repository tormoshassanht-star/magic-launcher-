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
