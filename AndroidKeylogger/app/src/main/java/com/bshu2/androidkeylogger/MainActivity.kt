package com.bshu2.androidkeylogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast

/** Consent gate and status screen. Capture cannot start without agreement here. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var agree: CheckBox
    private lateinit var enable: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        agree = findViewById(R.id.agree)
        enable = findViewById(R.id.enable)

        if (Prefs.hasConsent(this)) {
            agree.isChecked = true
            agree.isEnabled = false
        }

        enable.setOnClickListener { onEnableClicked() }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.revoke).setOnClickListener { onRevokeClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val consent = Prefs.hasConsent(this)
        val capture = Prefs.captureEnabled(this)
        val upload = Prefs.uploadEnabled(this)
        status.text = when {
            !consent -> getString(R.string.status_no_consent)
            capture && upload -> getString(R.string.status_capture_upload)
            capture -> getString(R.string.status_capture_local)
            else -> getString(R.string.status_paused)
        }
    }

    private fun onEnableClicked() {
        if (!Prefs.hasConsent(this)) {
            if (!agree.isChecked) {
                Toast.makeText(this, R.string.consent_required, Toast.LENGTH_LONG).show()
                return
            }
            Prefs.grantConsent(this)
            agree.isEnabled = false
        }
        requestNotificationPermission()
        openAccessibilitySettings()
        refreshStatus()
    }

    private fun onRevokeClicked() {
        Prefs.revokeConsent(this)
        MonitoringNotification.cancel(this)
        AlarmScheduler.cancel(this)
        UploadPipeline.deleteAll(this)
        agree.isEnabled = true
        agree.isChecked = false
        refreshStatus()
        Toast.makeText(this, R.string.consent_revoked, Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.enable_service_hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
