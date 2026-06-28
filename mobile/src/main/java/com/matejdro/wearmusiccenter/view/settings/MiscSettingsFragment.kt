package com.matejdro.wearmusiccenter.view.settings

import android.Manifest
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.matejdro.wearmusiccenter.NotificationService
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.common.model.AutoStartMode
import com.matejdro.wearmusiccenter.util.launchWithPlayServicesErrorHandling
import com.matejdro.wearutils.logging.LogRetrievalTask
import com.matejdro.wearutils.preferences.compat.PreferenceFragmentCompatEx
import com.matejdro.wearutils.preferences.definition.Preferences
import com.matejdro.wearutils.preferencesync.PreferencePusher
import de.psdev.licensesdialog.LicensesDialog


class MiscSettingsFragment : PreferenceFragmentCompatEx(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val VIBRATION_CENTER_PACKAGE = "com.matejdro.wearvibrationcenter"
        private const val PREF_DEV_MODE = "developer_mode_enabled"
        private const val DEV_CLICKS_REQUIRED = 7
    }

    private var versionClickCount = 0
    private var devModeEnabled = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        devModeEnabled = preferenceManager.sharedPreferences?.getBoolean(PREF_DEV_MODE, false) == true

        initAppearanceSection()
        initAutomationSection()
        initNotificationsSection()
        initAboutSection()
        updateDevModeVisibility()
    }

    private fun initAppearanceSection() {
        findPreference<ListPreference>("app_theme")?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                applyTheme(newValue as String)
                true
            }

        val accentPref = findPreference<Preference>("custom_accent_color")
        updateAccentColorSummary(accentPref)
        accentPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showAccentColorPicker(accentPref)
            true
        }
    }

    private fun updateAccentColorSummary(pref: Preference?) {
        pref ?: return
        val saved = preferenceManager.sharedPreferences?.getString("custom_accent_color", null)
        pref.summary = if (saved != null) {
            getString(R.string.color_picker_current, saved)
        } else {
            getString(R.string.setting_custom_accent_color_description)
        }
    }

    private fun showAccentColorPicker(pref: Preference?) {
        val ctx = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val dp = ctx.resources.displayMetrics.density

        val currentHex = prefs.getString("custom_accent_color", null)
        val initialColor = if (currentHex != null) {
            try { Color.parseColor(currentHex) } catch (e: Exception) { 0xFF86A69D.toInt() }
        } else 0xFF86A69D.toInt()

        // Live preview swatch
        val previewSwatch = android.view.View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp * 8f
                setColor(initialColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (dp * 44f).toInt()
            ).also { it.bottomMargin = (dp * 12f).toInt() }
        }

        val picker = HSVColorPickerView(ctx).apply {
            setColor(initialColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (dp * 260f).toInt()
            )
        }

        var selectedColor = initialColor
        picker.onColorChanged = { color ->
            selectedColor = color
            (previewSwatch.background as? GradientDrawable)?.setColor(color)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (dp * 20f).toInt()
            setPadding(pad, (dp * 12f).toInt(), pad, (dp * 8f).toInt())
            addView(previewSwatch)
            addView(picker)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.color_picker_title)
            .setView(root)
            .setNeutralButton(R.string.color_picker_reset) { _, _ ->
                prefs.edit().remove("custom_accent_color").apply()
                updateAccentColorSummary(pref)
                (activity as? com.matejdro.wearmusiccenter.view.mainactivity.MainActivity)
                    ?.onCustomAccentColorChanged(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.color_picker_apply) { _, _ ->
                val hex = String.format("#%06X", 0xFFFFFF and selectedColor)
                prefs.edit().putString("custom_accent_color", hex).apply()
                updateAccentColorSummary(pref)
                (activity as? com.matejdro.wearmusiccenter.view.mainactivity.MainActivity)
                    ?.onCustomAccentColorChanged(hex)
            }
            .show()
    }

    private fun applyTheme(value: String) {
        val mode = when (value) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun initAutomationSection() {
        migrateOldAutoStartSetting()

        findPreference<Preference>("auto_start_apps_blacklist")?.isEnabled =
                Preferences.getEnum(preferenceManager.sharedPreferences, MiscPreferences.AUTO_START_MODE) != AutoStartMode.OFF

        findPreference<Preference>("auto_start_mode")!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    newValue as String
                    val mode = enumValueOf<AutoStartMode>(newValue)
                    findPreference<Preference>("auto_start_apps_blacklist")?.isEnabled = mode != AutoStartMode.OFF

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (newValue != AutoStartMode.OFF) {
                            android.service.notification.NotificationListenerService.requestRebind(
                                    ComponentName(requireContext(), NotificationService::class.java)
                            )
                        } else {
                            val serviceStopIntent = Intent(requireContext(), NotificationService::class.java)
                            serviceStopIntent.action = NotificationService.ACTION_UNBIND_SERVICE
                            requireContext().startService(serviceStopIntent)
                        }
                    }
                    true
                }
    }

    private fun migrateOldAutoStartSetting() {
        val preferences = preferenceManager.sharedPreferences!!
        if (preferences.contains("auto_start")) {
            val legacyAutoStart = Preferences.getBoolean(preferences, MiscPreferences.AUTO_START)
            val autoStartMode = if (legacyAutoStart) AutoStartMode.OPEN_APP else AutoStartMode.OFF
            findPreference<ListPreference>("auto_start_mode")?.value = autoStartMode.name
            preferences.edit().remove("auto_start").apply()
        }
    }

    private fun initNotificationsSection() {
        findPreference<Preference>("enable_notification_popup")!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference, value: Any ->
                if (value == false) return@OnPreferenceChangeListener true

                if (!isVibrationCenterInstalledAndEnabled()) {
                    AlertDialog.Builder(requireContext())
                            .setTitle(R.string.app_required)
                            .setMessage(R.string.vibration_center_required_description)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.open_play_store) { _: DialogInterface, _: Int ->
                                openVibrationCenterPlayStore()
                            }
                            .show()
                    return@OnPreferenceChangeListener false
                }
                true
            }
    }

    private fun initAboutSection() {
        findPreference<Preference>("supportButton")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                sendLogs()
                true
            }

        val versionPref = findPreference<Preference>("version")!!
        try {
            versionPref.summary =
                requireActivity().packageManager.getPackageInfo(requireActivity().packageName, 0).versionName
        } catch (ignored: PackageManager.NameNotFoundException) {}

        versionPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            handleVersionClick()
            true
        }

        findPreference<Preference>("licenses")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                LicensesDialog.Builder(activity)
                        .setNotices(R.raw.notices)
                        .setIncludeOwnLicense(true)
                        .build()
                        .show()
                true
            }

        findPreference<Preference>("dev_build_info")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                showEasterEgg()
                true
            }
    }

    private fun handleVersionClick() {
        if (devModeEnabled) {
            showEasterEgg()
            return
        }

        versionClickCount++
        val remaining = DEV_CLICKS_REQUIRED - versionClickCount

        when {
            versionClickCount >= DEV_CLICKS_REQUIRED -> {
                devModeEnabled = true
                preferenceManager.sharedPreferences?.edit()?.putBoolean(PREF_DEV_MODE, true)?.apply()
                updateDevModeVisibility()
                Toast.makeText(requireContext(), R.string.dev_mode_unlocked, Toast.LENGTH_LONG).show()
                versionClickCount = 0
            }
            remaining <= 3 -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.dev_mode_clicks_remaining, remaining),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateDevModeVisibility() {
        findPreference<PreferenceCategory>("cat_developer")?.isVisible = devModeEnabled
    }

    private fun showEasterEgg() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.easter_egg_title)
            .setMessage(R.string.easter_egg_message)
            .setPositiveButton(R.string.easter_egg_ok, null)
            .show()
    }

    private fun isVibrationCenterInstalledAndEnabled(): Boolean {
        return try {
            val appInfo = requireContext().packageManager.getApplicationInfo(VIBRATION_CENTER_PACKAGE, 0)
            appInfo.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openVibrationCenterPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VIBRATION_CENTER_PACKAGE")))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$VIBRATION_CENTER_PACKAGE")))
        }
    }

    private fun sendLogs() {
        LogRetrievalTask(activity,
                CommPaths.MESSAGE_SEND_LOGS,
                "apps@matejdro.com",
                "com.matejdro.wearmusiccenter.logs").execute(null as Void?)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.isNotEmpty() &&
                permissions[0] == Manifest.permission.WRITE_EXTERNAL_STORAGE &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendLogs()
        }
    }

    override fun onStart() {
        super.onStart()
        preferenceManager.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceManager.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "app_theme") return
        pushPreferencesToWatch()
    }

    private fun pushPreferencesToWatch() {
        lifecycleScope.launchWithPlayServicesErrorHandling(requireContext().applicationContext) {
            PreferencePusher.pushPreferences(
                    requireContext().applicationContext,
                    preferenceManager.sharedPreferences!!,
                    CommPaths.PREFERENCES_PREFIX,
                    false
            )
        }
    }
}
