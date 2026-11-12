package com.bshu2.androidkeylogger.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/** Route: owns the ViewModel and lifecycle wiring. Screen stays stateless. */
@Composable
fun HomeRoute(
    onOpenAccessibility: () -> Unit,
    onRevoked: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val status by vm.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(2000)
        }
    }
    HomeScreen(
        status = status,
        onSetCapture = vm::setCapture,
        onGrantConsent = vm::grantConsent,
        onOpenAccessibility = onOpenAccessibility,
        onRevoke = { vm.revoke(); onRevoked() },
    )
}
