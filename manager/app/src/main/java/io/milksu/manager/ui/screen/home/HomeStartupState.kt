package io.milksu.manager.ui.screen.home

import io.milksu.manager.data.model.MainTab
import io.milksu.manager.ui.screen.kpm.KpmUiState

data class HomeStartupState(
    val cachedSusfsEnabled: Boolean? = null,
    val susfsReadComplete: Boolean = false,
) {
    fun completeSusfsQuery(confirmedEnabled: Boolean?) = copy(
        cachedSusfsEnabled = confirmedEnabled ?: cachedSusfsEnabled,
        susfsReadComplete = true,
    )

    fun awaitingContent(integration: KpmUiState, selectedTab: String): Boolean {
        val awaitingKpm = (!integration.navigationReady || selectedTab == MainTab.KPM.name) &&
            integration.status == null && !integration.hasRead &&
            integration.unavailable == null && integration.error == null
        return (cachedSusfsEnabled == null && !susfsReadComplete) || awaitingKpm
    }
}
