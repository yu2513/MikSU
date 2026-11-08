package io.milksu.manager.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.milksu.manager.data.repository.SettingsRepository
import io.milksu.manager.data.repository.SettingsRepositoryImpl
import io.milksu.manager.data.model.MainTab
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.UiMode
import io.milksu.manager.ui.theme.ThemeController

class MainActivityViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val prefs = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val settingRepo: SettingsRepository = SettingsRepositoryImpl()
    private val mainPageState = MainPageState(savedStateHandle, prefs)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in observedKeys) {
            _uiState.value = readUiState()
        }
    }

    private val _uiState = MutableStateFlow(readUiState())
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()
    val selectedMainPage: StateFlow<Int> = mainPageState.selectedPage

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun setSelectedMainPage(page: Int) {
        mainPageState.updateSelectedPage(page)
    }

    private fun readUiState(): MainActivityUiState {
        return MainActivityUiState(
            appSettings = ThemeController.getAppSettings(),
            pageScale = settingRepo.pageScale,
            enableBlur = settingRepo.enableBlur,
            enableFloatingBottomBar = settingRepo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = settingRepo.enableFloatingBottomBarBlur,
            enableNavigationBadge = settingRepo.enableNavigationBadge,
            enableSwipeDismiss = settingRepo.enableSwipeDismiss,
            pagerInterceptionMode = settingRepo.pagerInterceptionMode,
            moduleDescriptionMaxLines = settingRepo.moduleDescriptionMaxLines,
            uiMode = UiMode.fromValue(settingRepo.uiMode),
        )
    }

    private companion object {
        val observedKeys = setOf(
            "color_mode",
            "key_color",
            "color_style",
            "color_spec",
            "page_scale",
            "module_description_max_lines",
            "enable_blur",
            "enable_floating_bottom_bar",
            "enable_floating_bottom_bar_blur",
            "enable_navigation_badge",
            "enable_swipe_dismiss",
            "pager_interception_mode",
            "ui_mode",
        )
    }
}

private const val SELECTED_MAIN_PAGE_KEY = "selected_main_page"
private const val SELECTED_MAIN_TAB_KEY = "selected_main_tab"

private class MainPageState(
    private val savedStateHandle: SavedStateHandle,
    private val preferences: SharedPreferences,
) {
    private val page = MutableStateFlow(
        MainTab.restore(
            savedStateHandle[SELECTED_MAIN_TAB_KEY] ?: preferences.getString(SELECTED_MAIN_TAB_KEY, null),
            savedStateHandle[SELECTED_MAIN_PAGE_KEY],
        ).ordinal
    )
    val selectedPage: StateFlow<Int> = page.asStateFlow()

    fun updateSelectedPage(page: Int) {
        val tab = MainTab.entries[MainPagerConfig.coercePage(page)]
        savedStateHandle[SELECTED_MAIN_TAB_KEY] = tab.name
        preferences.edit().putString(SELECTED_MAIN_TAB_KEY, tab.name).apply()
        this.page.value = tab.ordinal
    }
}

object MainPagerConfig {
    val PAGE_COUNT = MainTab.entries.size
    val LAST_PAGE_INDEX = PAGE_COUNT - 1

    fun coercePage(page: Int): Int = page.coerceIn(0, LAST_PAGE_INDEX)
}
