package com.bshu2.androidkeylogger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bshu2.androidkeylogger.R

@Composable
fun UploadToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_upload_enabled),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_upload_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DestinationFields(
    form: SettingsForm,
    onUpdate: ((SettingsForm) -> SettingsForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.botToken,
            onValueChange = { v -> onUpdate { it.copy(botToken = v) } },
            label = { Text(stringResource(R.string.settings_bot_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                // Marks the node as a password for accessibility too, so the
                // capture guard skips it. PasswordVisualTransformation only
                // masks the glyphs and leaves the plaintext exposed.
                .semantics { password() },
        )
        OutlinedTextField(
            value = form.chatId,
            onValueChange = { v -> onUpdate { it.copy(chatId = v) } },
            label = { Text(stringResource(R.string.settings_chat_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.intervalMinutes,
            onValueChange = { v -> onUpdate { it.copy(intervalMinutes = v) } },
            label = { Text(stringResource(R.string.settings_interval)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.blocklist,
            onValueChange = { v -> onUpdate { it.copy(blocklist = v) } },
            label = { Text(stringResource(R.string.settings_blocklist)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_delete_all),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
