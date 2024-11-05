package com.keymo.keymocast

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<SwitchPreferenceCompat>("night_mode")?.setOnPreferenceChangeListener { _, newValue ->
                val nightMode = if (newValue as Boolean) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(nightMode)
                true
            }

            findPreference<Preference>("port_number")?.setOnPreferenceChangeListener { _, newValue ->
                val portStr = newValue as String
                try {
                    val port = portStr.toInt()
                    port in 1024..65535
                } catch (e: NumberFormatException) {
                    false
                }
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            if (!prefs.contains("double_tap_time")) {
                findPreference<SeekBarPreference>("double_tap_time")?.value = 300
            }
            if (!prefs.contains("scroll_scaling")) {
                findPreference<SeekBarPreference>("scroll_scaling")?.value = 50
            }
            if (!prefs.contains("move_threshold")) {
                findPreference<SeekBarPreference>("move_threshold")?.value = 10
            }
            if (!prefs.contains("click_timeout")) {
                findPreference<SeekBarPreference>("click_timeout")?.value = 150
            }
        }
    }

    companion object {
        private const val DEFAULT_DOUBLE_TAP_TIME = 300L //ms
        private const val DEFAULT_MOVE_THRESHOLD = 10f //pixels
        private const val DEFAULT_CLICK_TIMEOUT = 150L //ms

        private const val KEY_DOUBLE_TAP_TIME = "double_tap_time"
        private const val KEY_SCROLL_SCALING = "scroll_scaling"
        private const val KEY_MOVE_THRESHOLD = "move_threshold"
        private const val KEY_CLICK_TIMEOUT = "click_timeout"

        fun getDoubleTapTime(prefs: android.content.SharedPreferences): Long =
            prefs.getInt(KEY_DOUBLE_TAP_TIME, DEFAULT_DOUBLE_TAP_TIME.toInt()).toLong()

        fun getScrollScaling(prefs: android.content.SharedPreferences): Float =
            prefs.getInt(KEY_SCROLL_SCALING, 50).toFloat() / 100f

        fun getMoveThreshold(prefs: android.content.SharedPreferences): Float =
            prefs.getInt(KEY_MOVE_THRESHOLD, DEFAULT_MOVE_THRESHOLD.toInt()).toFloat()

        fun getClickTimeout(prefs: android.content.SharedPreferences): Long =
            prefs.getInt(KEY_CLICK_TIMEOUT, DEFAULT_CLICK_TIMEOUT.toInt()).toLong()
    }
}