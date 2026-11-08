package io.milksu.manager.ui.screen.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.milksu.manager.ui.screen.kpm.KpmUiState
import io.milksu.manager.ui.screen.kpm.SusfsState

fun susfsHomeSnapshots(
    integrationState: StateFlow<KpmUiState>,
    refreshRequests: StateFlow<Int>,
): Flow<SusfsHomeSnapshot> = combine(
    integrationState.map { SusfsHomeSnapshot.from(it) }.distinctUntilChanged(),
    refreshRequests,
) { snapshot, _ -> snapshot }

data class SusfsHomeSnapshot(
    val state: SusfsState,
    val integrationEnabled: Boolean,
    val unavailable: String? = null,
) {
    val confirmed: Boolean
        get() = unavailable == null && state.queryError == null &&
            state.supported != null && state.loaded != null &&
            state.builtin != null && state.autoload != null

    val operational: Boolean
        get() = confirmed && integrationEnabled && state.supported == true &&
            (state.loaded == true || state.builtin == true)

    companion object {
        fun from(state: KpmUiState) = SusfsHomeSnapshot(
            state = state.susfs,
            integrationEnabled = state.susfsIntegrationEnabled,
            unavailable = state.unavailable,
        )
    }
}

enum class SusfsAvailability { Unknown, Available, Unavailable }

data class SusfsHomeInfo(
    val integrationEnabled: Boolean = false,
    val availability: SusfsAvailability = SusfsAvailability.Unknown,
    val version: String? = null,
    val variant: String? = null,
    val builtin: Boolean? = null,
) {
    val supported: Boolean
        get() = availability == SusfsAvailability.Available

    fun merge(snapshot: SusfsHomeSnapshot): SusfsHomeInfo {
        if (!snapshot.confirmed) {
            return if (availability == SusfsAvailability.Unavailable) {
                copy(availability = SusfsAvailability.Unknown)
            } else this
        }
        if (!snapshot.integrationEnabled) return SusfsHomeInfo()
        if (snapshot.state.supported == false) {
            return SusfsHomeInfo(
                integrationEnabled = true,
                availability = SusfsAvailability.Unavailable,
            )
        }
        if (!snapshot.operational) return SusfsHomeInfo(integrationEnabled = true)

        val nextVersion = runtimeValue(snapshot.state.version)
        val sameProvider = supported && version == nextVersion && builtin == snapshot.state.builtin
        return SusfsHomeInfo(
            integrationEnabled = true,
            availability = SusfsAvailability.Available,
            version = nextVersion,
            variant = runtimeValue(snapshot.state.variant) ?: variant.takeIf { sameProvider },
            builtin = snapshot.state.builtin,
        )
    }

    fun summary(supportedLabel: String, unavailableLabel: String, unknownLabel: String): String =
        when (availability) {
            SusfsAvailability.Unknown -> unknownLabel
            SusfsAvailability.Unavailable -> unavailableLabel
            SusfsAvailability.Available ->
                "$supportedLabel | ${version ?: unknownLabel} (${variant ?: unknownLabel})"
        }

    companion object {
        fun runtimeValue(value: String?): String? = value?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) &&
                it.lowercase() !in setOf("unknown", "unavailable", "unsupported", "n/a", "null")
        }
    }
}
