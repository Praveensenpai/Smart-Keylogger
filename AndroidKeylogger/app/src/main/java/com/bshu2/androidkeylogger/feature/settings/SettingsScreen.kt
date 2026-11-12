package com.bshu2.androidkeylogger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bshu2.androidkeylogger.R

@Composable
fun SettingsScreen(
    form: SettingsForm,
    onUpdate: ((SettingsForm) -> SettingsForm) -> Unit,
    onSave: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UploadToggle(
            checked = form.uploadEnabled,
            onCheckedChange = { checked ->
                onUpdate { f -> f.copy(uploadEnabled = checked) }
            },
        )
        DestinationFields(form = form, onUpdate = onUpdate)
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.action_delete_all),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (confirmDelete) {
        DeleteDialog(
            onConfirm = { confirmDelete = false; onDeleteAll() },
            onDismiss = { confirmDelete = false },
        )
    }
}
