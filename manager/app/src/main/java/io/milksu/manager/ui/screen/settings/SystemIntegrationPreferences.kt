package io.milksu.manager.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.milksu.manager.R
import io.milksu.manager.ui.component.material.SegmentedSwitchItem
import io.milksu.manager.ui.component.material.SegmentedListItem
import io.milksu.manager.ui.screen.kpm.KpmUiState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

private val KpmUiState.integrationError: String?
    get() = (unavailable ?: integrationSettingsError ?: error?.message ?:
        status?.takeIf { !it.supported || it.transitionFailed }?.reason)
        ?.lineSequence()?.firstOrNull()?.take(160)

private val KpmUiState.susfsError: String?
    get() = (unavailable ?: susfs.error?.message ?: susfs.reason?.takeIf {
        susfs.loaded == true && susfs.supported == false
    })?.lineSequence()?.firstOrNull()?.take(160)

fun systemIntegrationPreferencesMaterial(
    state: KpmUiState,
    actions: SettingsScreenActions,
): List<@Composable () -> Unit> = buildList {
    add {
        if (state.showUpgradeIntegration) {
            SegmentedListItem(
                enabled = state.canUpgradeIntegration,
                headlineContent = { Text(stringResource(R.string.kpm_integration)) },
                leadingContent = { Icon(Icons.Filled.Memory, null) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(R.string.kpm_update_required),
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.integrationError?.let { Text(it) }
                    }
                },
                trailingContent = {
                    TextButton(
                        onClick = actions.onUpgradeKpmIntegration,
                        enabled = state.canUpgradeIntegration,
                    ) { Text(stringResource(R.string.module_update)) }
                },
            )
        } else {
            SegmentedSwitchItem(
                icon = Icons.Filled.Memory,
                title = stringResource(R.string.kpm_integration),
                summary = state.integrationError,
                checked = state.status?.configuredEnabled == true,
                enabled = state.canChangeIntegration,
                onCheckedChange = actions.onSetKpmIntegration,
            )
        }
    }
    add {
        SegmentedSwitchItem(
            icon = Icons.Filled.RestartAlt,
            title = stringResource(R.string.susfs_integration),
            summary = state.susfsError,
            checked = state.susfsIntegrationEnabled,
            enabled = state.canChangeSusfsIntegration,
            onCheckedChange = actions.onSetSusfsAutoload,
        )
    }
}

@Composable
fun SystemIntegrationPreferencesMiuix(
    state: KpmUiState,
    actions: SettingsScreenActions,
) {
    if (state.showUpgradeIntegration) {
        val required = stringResource(R.string.kpm_update_required)
        BasicComponent(
            title = stringResource(R.string.kpm_integration),
            summary = state.integrationError?.let { "$required\n$it" } ?: required,
            summaryColor = BasicComponentDefaults.summaryColor(
                color = MiuixTheme.colorScheme.error,
                disabledColor = MiuixTheme.colorScheme.error,
            ),
            startAction = {
                MiuixIcon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            },
            endActions = {
                MiuixTextButton(
                    text = stringResource(R.string.module_update),
                    onClick = actions.onUpgradeKpmIntegration,
                    enabled = state.canUpgradeIntegration,
                )
            },
            enabled = state.canUpgradeIntegration,
        )
    } else {
        SwitchPreference(
            title = stringResource(R.string.kpm_integration),
            summary = state.integrationError,
            startAction = {
                MiuixIcon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            },
            checked = state.status?.configuredEnabled == true,
            enabled = state.canChangeIntegration,
            onCheckedChange = actions.onSetKpmIntegration,
        )
    }
    SwitchPreference(
        title = stringResource(R.string.susfs_integration),
        summary = state.susfsError,
        startAction = {
            MiuixIcon(
                imageVector = Icons.Rounded.RestartAlt,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        },
        checked = state.susfsIntegrationEnabled,
        enabled = state.canChangeSusfsIntegration,
        onCheckedChange = actions.onSetSusfsAutoload,
    )
}
