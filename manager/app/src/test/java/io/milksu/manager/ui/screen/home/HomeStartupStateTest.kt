package io.milksu.manager.ui.screen.home

import io.milksu.manager.data.model.MainTab
import io.milksu.manager.data.system.BackendException
import io.milksu.manager.data.system.KpmReport
import io.milksu.manager.data.system.KpmStatus
import io.milksu.manager.ui.screen.kpm.KpmUiState
import org.junit.Assert.*
import org.junit.Test

class HomeStartupStateTest {
    private val cachedKpm = KpmUiState(kpmTabEnabled = true, navigationReady = true)
    private val home = MainTab.HOME.name

    @Test fun missingSusfsCacheWaitsEvenWhenKpmNavigationIsAlreadyCached() {
        assertTrue(HomeStartupState().awaitingContent(cachedKpm, home))
        assertEquals(5, MainTab.visible(cachedKpm.kpmTabEnabled).size)
    }

    @Test fun eitherConfirmedSusfsCacheAllowsImmediateFiveTabStartup() {
        for (enabled in listOf(false, true)) {
            val startup = HomeStartupState(cachedSusfsEnabled = enabled)
            assertFalse(startup.awaitingContent(cachedKpm, home))
            assertEquals(5, MainTab.visible(cachedKpm.kpmTabEnabled).size)
        }
    }

    @Test fun firstSusfsResultOpensContentWithoutAnyHomeObserver() {
        val startup = HomeStartupState().completeSusfsQuery(true)
        assertTrue(startup.susfsReadComplete)
        assertEquals(true, startup.cachedSusfsEnabled)
        assertFalse(startup.awaitingContent(cachedKpm, home))
    }

    @Test fun initialQueryFailureReleasesTheGateInsteadOfWaitingForever() {
        val startup = HomeStartupState().completeSusfsQuery(null)
        assertNull(startup.cachedSusfsEnabled)
        assertFalse(startup.awaitingContent(cachedKpm, home))
        assertFalse(startup.awaitingContent(
            KpmUiState(unavailable = "No root"), home,
        ))
    }

    @Test fun failedQueriesNeverTurnACachedEnableOff() {
        val startup = HomeStartupState(cachedSusfsEnabled = true).completeSusfsQuery(null)
        assertEquals(true, startup.cachedSusfsEnabled)
        assertFalse(startup.awaitingContent(cachedKpm.copy(unavailable = "No root"), home))
        assertEquals(5, MainTab.visible(cachedKpm.kpmTabEnabled).size)
    }

    @Test fun aConfirmedDisableReplacesTheCache() {
        assertEquals(false, HomeStartupState(cachedSusfsEnabled = true)
            .completeSusfsQuery(false).cachedSusfsEnabled)
    }

    @Test fun uncachedKpmStillWaitsForItsOwnResult() {
        val startup = HomeStartupState().completeSusfsQuery(true)
        assertTrue(startup.awaitingContent(KpmUiState(), home))
        val read = KpmUiState().merge(KpmReport(
            status = KpmStatus(true, true, true, false), modules = emptyList(),
        ))
        assertFalse(startup.awaitingContent(read, home))
        assertEquals(5, MainTab.visible(read.kpmTabEnabled).size)
    }

    @Test fun kpmQueryErrorAlsoReleasesUncachedStartup() {
        val startup = HomeStartupState().completeSusfsQuery(null)
        val failed = KpmUiState().merge(KpmReport(error = BackendException("query", "failed")))
        assertFalse(startup.awaitingContent(failed, home))
    }

    @Test fun selectedKpmPageKeepsItsExistingStatusRestoreGate() {
        val startup = HomeStartupState(cachedSusfsEnabled = false)
        assertTrue(startup.awaitingContent(cachedKpm, MainTab.KPM.name))
        assertFalse(startup.awaitingContent(cachedKpm.copy(hasRead = true), MainTab.KPM.name))
    }

    @Test fun metadataCacheRestoresBeforeContentWithoutWaitingForAHomeObserver() {
        val startup = HomeStartupState(cachedSusfsEnabled = true)
        val info = SusfsHomeCache(
            available = true, version = "v2.3.0", variant = "GKI-LKM", builtin = false,
        ).restore(integrationEnabled = true)
        assertFalse(startup.awaitingContent(cachedKpm, home))
        assertEquals(SusfsAvailability.Available, info.availability)
        assertEquals(info, info.merge(SusfsHomeSnapshot.from(cachedKpm)))
    }

    @Test fun missingMetadataNeverKeepsTheStartupGateClosedAfterAReadCompletes() {
        val startup = HomeStartupState().completeSusfsQuery(true)
        val info = SusfsHomeCache().restore(integrationEnabled = true)
        assertFalse(startup.awaitingContent(cachedKpm, home))
        assertEquals(SusfsAvailability.Unknown, info.availability)
        assertNotEquals(SusfsAvailability.Unavailable, info.availability)
    }
}
