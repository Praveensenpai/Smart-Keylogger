package com.bshu2.androidkeylogger.feature.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bshu2.androidkeylogger.data.LogTailer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LogsViewModel(app: Application) : AndroidViewModel(app) {

    private val tailer = LogTailer(app)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    suspend fun seed() {
        _lines.value = withContext(Dispatchers.IO) { tailer.seed() }
    }

    suspend fun poll() {
        _lines.value = withContext(Dispatchers.IO) { tailer.poll() }
    }
}
