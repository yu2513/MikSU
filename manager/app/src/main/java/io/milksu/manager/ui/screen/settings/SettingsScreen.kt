package io.milksu.manager.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.milksu.manager.R
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.LocalUiMode
import io.milksu.manager.ui.UiMode
import io.milksu.manager.ui.component.dialog.ManagerHideDialog
import io.milksu.manager.ui.component.dialog.rememberConfirmDialog
import io.milksu.manager.ui.navigation3.Navigator
import io.milksu.manager.ui.navigation3.Route
import io.milksu.manager.ui.util.ManagerHiding
import io.milksu.manager.ui.viewmodel.KpmViewModel
import io.milksu.manager.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<SettingsViewModel>()
    val settingsState by viewModel.uiState.collectAsStateWithLifecycle()
    val managerHideState by ManagerHiding.state.collectAsStateWithLifecycle()
    val uiState = settingsState.copy(
        managerHideSupported = managerHideState.supported,
        isManagerHidden = managerHideState.hidden,
        managerHideBusy = managerHideState.busy,
        managerHideError = managerHideState.error,
    )
    val systemViewModel = viewModel<KpmViewModel>(viewModelStoreOwner = ksuApp)
    val systemState by systemViewModel.uiState.collectAsStateWithLifecycle()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }
    var showManagerHideDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            viewModel.refresh()
            ManagerHiding.refresh()
            systemViewModel.refresh(includeSusfs = true, verifyIntegration = true)
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
            ManagerHiding.refresh()
            systemViewModel.refresh(includeSusfs = true, verifyIntegration = true)
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    var pendingIntegration by rememberSaveable { mutableStateOf(false) }
    val latestSystemState by rememberUpdatedState(systemState)
    val integrationDialog = rememberConfirmDialog(onConfirm = {
        if (latestSystemState.canSetIntegration(pendingIntegration)) {
            systemViewModel.integrate(pendingIntegration)
        }
    })
    val integrationTitle = stringResource(R.string.kpm_integration)

    ManagerHideDialog(
        show = showManagerHideDialog,
        onDismiss = { showManagerHideDialog = false },
        onConfirm = { displayName, iconUri ->
            showManagerHideDialog = false
            if (ManagerHiding.checkSupport()) {
                ManagerHiding.setHidden(true, displayName = displayName, iconUri = iconUri)
            }
        },
    )

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetUiModeIndex = { index ->
            viewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenProfileTemplate = { navigator.push(Route.AppProfileTemplate) },
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetAvcSpoofEnabled = viewModel::setAvcSpoofEnabled,
        onSetManagerHidden = { enabled ->
            if (enabled) {
                if (!managerHideState.busy && ManagerHiding.checkSupport()) {
                    showManagerHideDialog = true
                }
            } else {
                ManagerHiding.setHidden(false)
            }
        },
        onSetSulogEnabled = viewModel::setSulogEnabled,
        onSetAdbRootEnabled = viewModel::setAdbRootEnabled,
        onSetShellSuAdbOnlyEnabled = viewModel::setShellSuAdbOnlyEnabled,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onSetAutoJailbreak = viewModel::setAutoJailbreak,
        onSetUseSoftReboot = viewModel::setUseSoftReboot,
        onOpenAbout = { navigator.push(Route.About) },
        onSetKpmIntegration = { enabled ->
            if (systemState.canSetIntegration(enabled)) {
                pendingIntegration = enabled
                integrationDialog.showConfirm(title = integrationTitle)
            }
        },
        onUpgradeKpmIntegration = {
            if (latestSystemState.canUpgradeIntegration) {
                systemViewModel.upgradeIntegration()
            }
        },
        onSetSusfsAutoload = { enabled ->
            if (systemState.canChangeSusfsIntegration) {
                systemViewModel.susfsAction(if (enabled) "enable" else "disable")
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, actions, bottomInnerPadding, systemState)
        UiMode.Material -> SettingPagerMaterial(uiState, actions, bottomInnerPadding, systemState)
    }
}
