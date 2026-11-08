package io.milksu.manager.ui.viewmodel

import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.milksu.manager.Natives
import io.milksu.manager.R
import io.milksu.manager.data.repository.SettingsRepository
import io.milksu.manager.data.repository.SettingsRepositoryImpl
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.screen.settings.SettingsUiState
import io.milksu.manager.ui.theme.ColorMode

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val featureMutation = Mutex()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            featureMutation.withLock {
            val checkUpdate = repo.checkUpdate
            val checkModuleUpdate = repo.checkModuleUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableSwipeDismiss = repo.enableSwipeDismiss
            val pagerInterceptionMode = repo.pagerInterceptionMode
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val enableNavigationBadge = repo.enableNavigationBadge
            val pageScale = repo.pageScale
            val moduleDescriptionMaxLines = repo.moduleDescriptionMaxLines
            val enableWebDebugging = repo.enableWebDebugging
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val isLkmMode = repo.isLkmMode()

            // Async loading for natives/features
            val suCompatStatus = repo.getSuCompatStatus()
            val suCompatPersistValue = repo.getSuCompatPersistValue()
            val isSuEnabled = repo.isSuEnabled()

            // Forced features show the value in effect, the persisted one is ignored
            val suCompatMode = when {
                suCompatStatus == "forced" -> if (isSuEnabled) 0 else 2
                suCompatPersistValue == 0L -> 2
                !isSuEnabled -> 1
                else -> 0
            }

            val kernelUmountStatus = repo.getKernelUmountStatus()
            val isKernelUmountEnabled = repo.isKernelUmountEnabled()
            val selinuxHideStatus = repo.getSelinuxHideStatus()
            val isSelinuxHideEnabled = repo.isSelinuxHideEnabled()
            val avcSpoofStatus = repo.getAvcSpoofStatus()
            val avcSpoofValue = repo.getAvcSpoofValue()
            val sulogStatus = repo.getSulogStatus()
            val isSulogEnabled = repo.getSulogValue()?.let { it == 1L } ?: _uiState.value.isSulogEnabled
            val adbRootStatus = repo.getAdbRootStatus()
            val isAdbRootEnabled = repo.getAdbRootValue()?.let { it == 1L } ?: _uiState.value.isAdbRootEnabled
            val shellSuAdbOnlyStatus = repo.getShellSuAdbOnlyStatus()
            val isShellSuAdbOnlyEnabled = repo.getShellSuAdbOnlyValue()?.let { it == 1L } ?: _uiState.value.isShellSuAdbOnlyEnabled
            val isDefaultUmountModules = repo.isDefaultUmountModules()
            val uiMode = repo.uiMode
            val autoJailbreak = repo.autoJailbreak
            val useSoftReboot = repo.useSoftReboot
            val isLateLoadMode = Natives.isLateLoadMode

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    checkModuleUpdate = checkModuleUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableSwipeDismiss = enableSwipeDismiss,
                    pagerInterceptionMode = pagerInterceptionMode,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    enableNavigationBadge = enableNavigationBadge,
                    pageScale = pageScale,
                    moduleDescriptionMaxLines = moduleDescriptionMaxLines,
                    enableWebDebugging = enableWebDebugging,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    suCompatStatus = suCompatStatus,
                    suCompatMode = suCompatMode,
                    isSuEnabled = isSuEnabled,
                    adbRootStatus = adbRootStatus,
                    isAdbRootEnabled = isAdbRootEnabled,
                    shellSuAdbOnlyStatus = shellSuAdbOnlyStatus,
                    isShellSuAdbOnlyEnabled = isShellSuAdbOnlyEnabled,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    selinuxHideStatus = selinuxHideStatus,
                    isSelinuxHideEnabled = isSelinuxHideEnabled,
                    avcSpoofStatus = avcSpoofStatus,
                    isAvcSpoofEnabled = if (it.avcSpoofBusy || avcSpoofValue == null) {
                        it.isAvcSpoofEnabled
                    } else {
                        avcSpoofValue == 1L
                    },
                    sulogStatus = sulogStatus,
                    isSulogEnabled = isSulogEnabled,
                    isDefaultUmountModules = isDefaultUmountModules,
                    isLkmMode = isLkmMode,
                    autoJailbreak = autoJailbreak,
                    useSoftReboot = useSoftReboot,
                    isLateLoadMode = isLateLoadMode,
                )
            }
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        val newThemeMode = when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setCheckModuleUpdate(enabled: Boolean) {
        repo.checkModuleUpdate = enabled
        _uiState.update { it.copy(checkModuleUpdate = enabled) }
    }

    fun setThemeMode(mode: Int) {
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableSwipeDismiss(enabled: Boolean) {
        repo.enableSwipeDismiss = enabled
        _uiState.update { it.copy(enableSwipeDismiss = enabled) }
    }

    fun setPagerInterceptionMode(mode: Int) {
        repo.pagerInterceptionMode = mode
        _uiState.update { it.copy(pagerInterceptionMode = mode.coerceIn(0, 2)) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setEnableNavigationBadge(enabled: Boolean) {
        repo.enableNavigationBadge = enabled
        _uiState.update { it.copy(enableNavigationBadge = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setModuleDescriptionMaxLines(lines: Int) {
        repo.moduleDescriptionMaxLines = lines
        _uiState.update { it.copy(moduleDescriptionMaxLines = lines) }
    }

    fun setEnableWebDebugging(enabled: Boolean) {
        repo.enableWebDebugging = enabled
        _uiState.update { it.copy(enableWebDebugging = enabled) }
    }

    fun setSuCompatMode(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            featureMutation.withLock {
                if (mode !in 0..2) return@withLock
                val persistentEnabled = mode != 2
                val changed = repo.setSuEnabled(persistentEnabled)
                val saved = changed && repo.execKsudFeatureSave("su_compat")
                if (saved) {
                    repo.setSuCompatModePref(if (persistentEnabled) 0 else 2)
                }
                val runtimeChanged = if (mode == 1 && saved) repo.setSuEnabled(false) else changed
                val actual = repo.isSuEnabled()
                val persisted = repo.getSuCompatPersistValue()
                _uiState.update {
                    it.copy(
                        isSuEnabled = actual,
                        suCompatMode = if (actual) 0 else if (persisted == 0L) 2 else 1,
                    )
                }
                if (!saved || !runtimeChanged) featureSaveFailed()
            }
        }
    }

    private suspend fun featureSaveFailed() {
        withContext(Dispatchers.Main) {
            Toast.makeText(ksuApp, R.string.settings_feature_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setPersistentFeature(
        feature: String,
        enabled: Boolean,
        setter: (Boolean) -> Boolean,
        read: suspend () -> Boolean?,
        update: (SettingsUiState, Boolean) -> SettingsUiState,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            featureMutation.withLock {
                val changed = setter(enabled)
                val actual = read()
                val saved = changed && actual == enabled && repo.execKsudFeatureSave(feature)
                if (actual != null) _uiState.update { update(it, actual) }
                if (!saved) featureSaveFailed()
            }
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            featureMutation.withLock {
                val saved = repo.setKernelUmountEnabled(enabled)
                val actual = repo.isKernelUmountEnabled()
                _uiState.update { it.copy(isKernelUmountEnabled = actual) }
                if (!saved || actual != enabled) featureSaveFailed()
            }
        }
    }

    fun setSelinuxHideEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            featureMutation.withLock {
                val status = repo.setSelinuxHideEnabled(enabled)
                val saved = (status == 0 || status == -OsConstants.EAGAIN) &&
                    repo.execKsudFeatureSave("selinux_hide")
                _uiState.update { it.copy(isSelinuxHideEnabled = repo.isSelinuxHideEnabled()) }
                if (!saved && (status == 0 || status == -OsConstants.EAGAIN)) featureSaveFailed()
                when (status) {
                    0 -> {}
                    -OsConstants.EAGAIN -> withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, R.string.settings_selinux_hide_reboot_required, Toast.LENGTH_LONG).show()
                    }
                    else -> withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, ksuApp.getString(R.string.settings_selinux_hide_failed, status), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun setAvcSpoofEnabled(enabled: Boolean) {
        if (_uiState.value.avcSpoofBusy || _uiState.value.avcSpoofStatus != "supported") return
        _uiState.update { it.copy(avcSpoofBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            featureMutation.withLock {
                var saved = false
                try {
                    val changed = repo.setAvcSpoofEnabled(enabled)
                    val actual = repo.getAvcSpoofValue()
                    saved = changed && actual == (if (enabled) 1L else 0L) && repo.execKsudFeatureSave("avc_spoof")
                    if (actual != null) _uiState.update { it.copy(isAvcSpoofEnabled = actual == 1L) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    saved = false
                } finally {
                    _uiState.update { it.copy(avcSpoofBusy = false) }
                }
                if (!saved) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, R.string.settings_avc_spoof_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun setAutoJailbreak(enabled: Boolean) {
        repo.autoJailbreak = enabled
        _uiState.update { it.copy(autoJailbreak = enabled) }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }

    fun setSulogEnabled(enabled: Boolean) {
        setPersistentFeature("sulog", enabled, repo::setSulogEnabled,
            { repo.getSulogValue()?.let { it == 1L } }) { state, actual ->
            state.copy(isSulogEnabled = actual)
        }
    }

    fun setShellSuAdbOnlyEnabled(enabled: Boolean) {
        setPersistentFeature("shell_su_adb_only", enabled, repo::setShellSuAdbOnlyEnabled,
            { repo.getShellSuAdbOnlyValue()?.let { it == 1L } }) { state, actual ->
            state.copy(isShellSuAdbOnlyEnabled = actual)
        }
    }

    fun setAdbRootEnabled(enabled: Boolean) {
        setPersistentFeature("adb_root", enabled, repo::setAdbRootEnabled,
            { repo.getAdbRootValue()?.let { it == 1L } }) { state, actual ->
            state.copy(isAdbRootEnabled = actual)
        }
    }

    fun setDefaultUmountModules(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setDefaultUmountModules(enabled)) {
                _uiState.update { it.copy(isDefaultUmountModules = enabled) }
            }
        }
    }
}
