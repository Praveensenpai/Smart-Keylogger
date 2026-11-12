package com.bshu2.androidkeylogger.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bshu2.androidkeylogger.AlarmScheduler
import com.bshu2.androidkeylogger.MonitoringNotification
import com.bshu2.androidkeylogger.Prefs
import com.bshu2.androidkeylogger.UploadPipeline
import com.bshu2.androidkeylogger.data.StatusRepository
import com.bshu2.androidkeylogger.data.StatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _status = MutableStateFlow(StatusRepository.snapshot(app))
    val status: StateFlow<StatusSnapshot> = _status.asStateFlow()

    fun refresh() {
        _status.value = StatusRepository.snapshot(getApplication())
    }

    fun setCapture(enabled: Boolean) {
        val app = getApplication<Application>()
        Prefs.setCaptureEnabled(app, enabled)
        MonitoringNotification.update(app)
        refresh()
    }

    fun grantConsent() {
        val app = getApplication<Application>()
        Prefs.grantConsent(app)
        refresh()
    }

    fun revoke() {
        val app = getApplication<Application>()
        Prefs.revokeConsent(app)
        MonitoringNotification.cancel(app)
        AlarmScheduler.cancel(app)
        UploadPipeline.deleteAll(app)
        refresh()
    }
}
