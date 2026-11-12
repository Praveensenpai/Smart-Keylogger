package com.bshu2.androidkeylogger.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsRoute(vm: SettingsViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    SettingsScreen(
        form = form,
        onUpdate = vm::update,
        onSave = vm::save,
        onDeleteAll = vm::deleteAll,
    )
}
