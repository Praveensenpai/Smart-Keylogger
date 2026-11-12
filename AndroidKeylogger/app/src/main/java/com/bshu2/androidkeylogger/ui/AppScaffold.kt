package com.bshu2.androidkeylogger.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.bshu2.androidkeylogger.R
import com.bshu2.androidkeylogger.feature.home.HomeRoute
import com.bshu2.androidkeylogger.feature.logs.LogsRoute
import com.bshu2.androidkeylogger.feature.settings.SettingsRoute

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LOGS(R.string.tab_logs, Icons.Filled.List),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun AppScaffold(onOpenAccessibility: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner)) {
            when (current) {
                Tab.HOME -> HomeRoute(
                    onOpenAccessibility = onOpenAccessibility,
                    onRevoked = { current = Tab.HOME },
                )
                Tab.LOGS -> LogsRoute()
                Tab.SETTINGS -> SettingsRoute()
            }
        }
    }
}
