package io.milksu.manager.ui.screen.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import io.milksu.manager.data.system.BackendException
import io.milksu.manager.data.system.CommandOutput
import io.milksu.manager.data.system.SusfsProtocol
import io.milksu.manager.ui.screen.kpm.KpmUiState
import io.milksu.manager.ui.screen.kpm.SusfsState
import org.junit.Assert.*
import org.junit.Test

class SusfsHomeInfoTest {
    private fun state(
        enabled: Boolean = true,
        supported: Boolean = true,
        loaded: Boolean = true,
        builtin: Boolean = false,
        version: String = "v2.3.0",
        variant: String? = "GKI-LKM",
    ) = KpmUiState(
        susfs = SusfsState(
            autoload = enabled, supported = supported, loaded = loaded,
            builtin = builtin, version = version, variant = variant,
        ),
    )

    @Test fun disabledIntegrationHidesEvenAnAvailableBuiltinProvider() {
        val snapshot = SusfsHomeSnapshot.from(state(enabled = false, loaded = false, builtin = true))
        val info = SusfsHomeInfo(integrationEnabled = true).merge(snapshot)
        assertTrue(snapshot.confirmed)
        assertFalse(info.integrationEnabled)
        assertFalse(info.supported)
        assertNull(info.version)
        assertNull(info.variant)
    }

    @Test fun cachedEnableKeepsTheRowDuringInitialStatusReadWithoutClaimingSupport() {
        val info = SusfsHomeInfo(integrationEnabled = true).merge(SusfsHomeSnapshot.from(KpmUiState()))
        assertTrue(info.integrationEnabled)
        assertFalse(info.supported)
        assertEquals(SusfsAvailability.Unknown, info.availability)
        assertNull(info.version)
    }

    @Test fun actualRuntimeVersionIsUsedAndVariantSurvivesEquivalentRefreshes() {
        val snapshot = SusfsHomeSnapshot.from(state(version = " v2.3.0 ", variant = " GKI-LKM "))
        val info = SusfsHomeInfo().merge(snapshot)
        assertTrue(info.integrationEnabled)
        assertTrue(info.supported)
        assertEquals("v2.3.0", info.version)
        assertEquals("GKI-LKM", info.variant)
        assertEquals(info, info.merge(snapshot))
        assertNull(info.merge(SusfsHomeSnapshot.from(state(version = "v2.4.0", variant = null))).variant)
    }

    @Test fun enabledButStoppedOrFailedProviderIsNotReportedSupported() {
        for (current in listOf(
            state(loaded = false),
            state(supported = false),
            state(supported = false, loaded = false),
        )) {
            val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(current))
            assertTrue(info.integrationEnabled)
            assertFalse(info.supported)
            assertNull(info.version)
            assertNull(info.variant)
            assertEquals(
                if (current.susfs.supported == false) SusfsAvailability.Unavailable
                else SusfsAvailability.Unknown,
                info.availability,
            )
        }
    }

    @Test fun aResidentIndependentGuardUsesTheAuthoritativeEnabledState() {
        val current = state(enabled = false)
        assertTrue(current.susfsIntegrationEnabled)
        val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(current))
        assertTrue(info.integrationEnabled)
        assertTrue(info.supported)
    }

    @Test fun aConfirmedDisableClearsAllPreviousRuntimeDetails() {
        val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
            .merge(SusfsHomeSnapshot.from(state(enabled = false, supported = false, loaded = false)))
        assertEquals(SusfsHomeInfo(), info)
    }

    @Test fun queryFailurePreservesTheLastConfirmedRuntimeInformation() {
        val previous = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
        val failed = state().let {
            it.copy(susfs = it.susfs.queryFailed(BackendException("read", "failed")))
        }
        for (snapshot in listOf(
            SusfsHomeSnapshot.from(failed),
            SusfsHomeSnapshot.from(state().copy(unavailable = "No root")),
        )) {
            assertFalse(snapshot.confirmed)
            assertEquals(previous, previous.merge(snapshot))
        }
    }

    @Test fun unknownFieldsAndBusyFlagsDoNotProduceOffOnVisibilityChanges() {
        val previous = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
        for (partial in listOf(
            state().susfs.copy(supported = null), state().susfs.copy(loaded = null),
            state().susfs.copy(builtin = null), state().susfs.copy(autoload = null),
        )) {
            val snapshot = SusfsHomeSnapshot.from(KpmUiState(susfs = partial))
            assertFalse(snapshot.confirmed)
            assertEquals(previous, previous.merge(snapshot))
        }
        assertEquals(previous, previous.merge(SusfsHomeSnapshot.from(state().copy(busy = true))))
    }

    @Test fun invalidRuntimeMetadataIsNeverDisplayedAsRealVersionOrVariant() {
        for (value in listOf("", "  ", "Unknown", "Unsupported", "Unavailable", "null", "n/a", "v1\nv2", "x".repeat(129))) {
            assertNull(SusfsHomeInfo.runtimeValue(value))
        }
        assertEquals("SUSFS-LKM", SusfsHomeInfo.runtimeValue(" SUSFS-LKM\n"))
    }

    @Test fun statusVersionAndVariantAreMergedTogetherWithoutAnIntermediatePlaceholder() {
        val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
        assertEquals("Supported | v2.3.0 (GKI-LKM)", info.summary("Supported", "Unavailable", "Unknown"))
    }

    @Test fun statusJsonPassesVariantThroughTheSharedStateAndAcceptsOlderDaemons() {
        for (variantField in listOf("", ""","variant":null""", ""","variant":"GKI-LKM"""")) {
            val status = SusfsProtocol.decodeStatus(CommandOutput(
                0,
                """{"supported":true,"loaded":true,"builtin":false,"autoload":true,"version":"v2.3.0"$variantField}""",
                "",
            ))
            val state = KpmUiState(susfs = SusfsState().merge(status))
            val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state))
            assertTrue(info.supported)
            assertEquals("v2.3.0", info.version)
            assertEquals(if (variantField.contains("GKI-LKM")) "GKI-LKM" else null, info.variant)
        }
    }

    @Test fun missingVariantDoesNotMakeAnOperationalGuardUnavailable() {
        for (variant in listOf(
            null, "", "Unknown", "Unsupported", "Unavailable", "GKI-LKM\nextra output",
        )) {
            val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state(variant = variant)))
            assertEquals(SusfsAvailability.Available, info.availability)
            assertNull(info.variant)
            assertEquals("Supported | v2.3.0 (Unknown)", info.summary("Supported", "Unavailable", "Unknown"))
        }
    }

    @Test fun unknownAndExplicitlyUnavailableHaveDifferentSummaries() {
        val unknown = SusfsHomeInfo(integrationEnabled = true)
        assertEquals("Unknown", unknown.summary("Supported", "Unavailable", "Unknown"))
        val unavailable = unknown.merge(SusfsHomeSnapshot.from(state(supported = false)))
        assertEquals(SusfsAvailability.Unavailable, unavailable.availability)
        assertEquals("Unavailable", unavailable.summary("Supported", "Unavailable", "Unknown"))
        assertEquals("Unknown", unavailable.merge(SusfsHomeSnapshot.from(KpmUiState()))
            .summary("Supported", "Unavailable", "Unknown"))
    }

    @Test fun aMissingVariantOnTheSameProviderRetainsTheConfirmedVariant() {
        val previous = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
        assertEquals(previous, previous.merge(SusfsHomeSnapshot.from(state(variant = null))))
        assertNull(previous.merge(SusfsHomeSnapshot.from(state(builtin = true, variant = null))).variant)
    }

    @Test fun homeRefreshReemitsTheAuthoritativeSnapshotWithoutAnExtraCommand() = runBlocking {
        withTimeout(5_000) {
            val integration = MutableStateFlow(state())
            val refresh = MutableStateFlow(0)
            val first = CompletableDeferred<SusfsHomeInfo>()
            val refreshed = CompletableDeferred<SusfsHomeInfo>()
            var count = 0
            val observer = launch {
                susfsHomeSnapshots(integration, refresh).collectLatest { snapshot ->
                    val info = SusfsHomeInfo().merge(snapshot)
                    if (++count == 1) first.complete(info) else refreshed.complete(info)
                }
            }
            try {
                val initial = first.await()
                refresh.value++
                assertEquals(initial, refreshed.await())
                assertEquals("GKI-LKM", initial.variant)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun observerRetainsConfirmedMetadataOnErrorsAndStopsWhenCancelled() = runBlocking {
        withTimeout(5_000) {
            val integration = MutableStateFlow(KpmUiState())
            val refresh = MutableStateFlow(0)
            val unknown = CompletableDeferred<Unit>()
            val active = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            var result = SusfsHomeInfo(integrationEnabled = true)
            var calls = 0
            val observer = launch {
                susfsHomeSnapshots(integration, refresh).collectLatest { snapshot ->
                    calls++
                    result = result.merge(snapshot)
                    if (snapshot.state.queryError != null) failed.complete(Unit)
                    else if (snapshot.operational) active.complete(Unit)
                    else unknown.complete(Unit)
                }
            }
            try {
                unknown.await()
                assertEquals(SusfsAvailability.Unknown, result.availability)
                integration.value = state()
                active.await()
                val confirmed = result
                integration.value = state().let {
                    it.copy(susfs = it.susfs.queryFailed(BackendException("query", "failed")))
                }
                failed.await()
                assertEquals(confirmed, result)
                observer.cancelAndJoin()
                val observedCalls = calls
                refresh.value++
                integration.value = state(version = "v2.4.0")
                yield()
                assertEquals(observedCalls, calls)
                assertEquals(confirmed, result)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun successfulReadOfAFailedOwnedGuardCannotBeOverriddenByItsVersion() {
        val failed = state(supported = false).let {
            it.copy(susfs = it.susfs.copy(reason = "Guard is resident but not operational"))
        }
        val previous = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(state()))
        val result = previous.merge(SusfsHomeSnapshot.from(failed))
        assertTrue(result.integrationEnabled)
        assertEquals(SusfsAvailability.Unavailable, result.availability)
        assertNull(result.version)
        assertNull(result.variant)
    }

    @Test fun actionFailureDoesNotOverrideAnOtherwiseConfirmedRuntimeStatus() {
        val running = state().let {
            it.copy(susfs = it.susfs.copy(error = BackendException("action", "failed")))
        }
        assertTrue(SusfsHomeSnapshot.from(running).confirmed)
        assertTrue(SusfsHomeInfo().merge(SusfsHomeSnapshot.from(running)).supported)
    }
}
