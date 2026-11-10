package com.bshu2.androidkeylogger

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

/** User-controlled destination, cadence, blocklist and data deletion. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val uploadEnabled = findViewById<CheckBox>(R.id.upload_enabled)
        val token = findViewById<EditText>(R.id.bot_token)
        val chat = findViewById<EditText>(R.id.chat_id)
        val interval = findViewById<EditText>(R.id.interval_min)
        val blocklist = findViewById<EditText>(R.id.blocklist)

        uploadEnabled.isChecked = Prefs.uploadEnabled(this)
        token.setText(Prefs.botToken(this))
        chat.setText(Prefs.chatId(this))
        interval.setText((Prefs.intervalMs(this) / 60_000L).toString())
        blocklist.setText(Prefs.blocklistText(this))

        findViewById<Button>(R.id.save).setOnClickListener {
            Prefs.setUploadEnabled(this, uploadEnabled.isChecked)
            Prefs.setDestination(this, token.text.toString(), chat.text.toString())
            val minutes = interval.text.toString().toLongOrNull() ?: 60L
            Prefs.setIntervalMs(this, minutes * 60_000L)
            Prefs.setBlocklist(this, blocklist.text.toString())
            AlarmScheduler.schedule(this)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.delete_all).setOnClickListener {
            UploadPipeline.deleteAll(this)
            Toast.makeText(this, R.string.logs_deleted, Toast.LENGTH_SHORT).show()
        }
    }
}
