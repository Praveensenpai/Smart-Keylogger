package com.bshu2.androidkeylogger.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bshu2.androidkeylogger.R
import com.bshu2.androidkeylogger.data.StatusSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    status: StatusSnapshot,
    onSetCapture: (Boolean) -> Unit,
    onGrantConsent: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRevoke: () -> Unit,
) {
    var showRevoke by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!status.consented) {
            ConsentCard(onGrantConsent)
        } else {
            ConsentedBody(
                status = status,
                onSetCapture = onSetCapture,
                onOpenAccessibility = onOpenAccessibility,
                onRevokeRequested = { showRevoke = true },
            )
        }
    }
    if (showRevoke) {
        RevokeDialog(
            onConfirm = { showRevoke = false; onRevoke() },
            onDismiss = { showRevoke = false },
        )
    }
}

@Composable
private fun ConsentedBody(
    status: StatusSnapshot,
    onSetCapture: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onRevokeRequested: () -> Unit,
) {
    StatusCard(
        icon = Icons.Filled.Accessibility,
        label = stringResource(R.string.label_service),
        value = onOff(status.serviceEnabled),
        hint = null,
    )
    StatusCard(
        icon = Icons.Filled.Keyboard,
        label = stringResource(R.string.label_capture),
        value = stringResource(
            if (status.captureActive) R.string.value_recording else R.string.value_paused,
        ),
        hint = if (!status.serviceEnabled) stringResource(R.string.master_switch_disabled) else null,
    )
    StatusCard(
        icon = Icons.Filled.CloudUpload,
        label = stringResource(R.string.label_upload),
        value = onOff(status.uploadEnabled && status.destinationConfigured),
        hint = null,
    )
    StatusCard(
        icon = Icons.Filled.Schedule,
        label = stringResource(R.string.label_last_upload),
        value = lastUpload(status.lastUploadAt),
        hint = null,
    )
    CaptureSwitch(
        checked = status.captureActive,
        enabled = status.serviceEnabled,
        onCheckedChange = onSetCapture,
    )
    if (!status.serviceEnabled) {
        Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_open_accessibility))
        }
    }
    TextButton(onClick = onRevokeRequested, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_revoke), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StatusCard(icon: ImageVector, label: String, value: String, hint: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CaptureSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.master_switch),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ConsentCard(onGrant: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.disclosure), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(stringResource(R.string.consent_agree))
            }
            Button(onClick = onGrant, enabled = agreed, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_enable))
            }
        }
    }
}

@Composable
private fun RevokeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.revoke_title)) },
        text = { Text(stringResource(R.string.revoke_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_confirm_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun onOff(on: Boolean): String =
    stringResource(if (on) R.string.value_on else R.string.value_off)

@Composable
private fun lastUpload(at: Long): String {
    if (at <= 0L) return stringResource(R.string.value_never)
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return fmt.format(Date(at))
}
