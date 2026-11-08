package io.milksu.manager.ui.screen.home

import io.milksu.manager.data.system.BackendException
import io.milksu.manager.ui.screen.kpm.KpmUiState
import io.milksu.manager.ui.screen.kpm.SusfsState
import org.junit.Assert.*
import org.junit.Test

class SusfsHomeCacheTest {
    private val running = KpmUiState(susfs = SusfsState(
        supported = true, loaded = true, builtin = false, autoload = true,
        version = "v2.3.0", variant = "GKI-LKM",
    ))
    private val confirmed = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(running))

    @Test fun cachedConfirmedDetailsRenderImmediatelyBeforeAnyHomeObserver() {
        val restored = SusfsHomeCache.from(confirmed).restore(integrationEnabled = true)
        assertEquals(confirmed, restored)
        assertEquals(restored, restored.merge(SusfsHomeSnapshot.from(KpmUiState())))
        assertEquals("Supported | v2.3.0 (GKI-LKM)", restored.summary("Supported", "Unavailable", "Unknown"))
    }

    @Test fun aCachedEnableWithoutRuntimeDetailsStartsUnknownRatherThanUnavailable() {
        val restored = SusfsHomeCache().restore(integrationEnabled = true)
        assertTrue(restored.integrationEnabled)
        assertEquals(SusfsAvailability.Unknown, restored.availability)
        assertEquals("Unknown", restored.summary("Supported", "Unavailable", "Unknown"))
    }

    @Test fun disabledIntegrationNeverExposesOldCachedDetails() {
        assertEquals(SusfsHomeInfo(), SusfsHomeCache.from(confirmed).restore(integrationEnabled = false))
    }

    @Test fun unknownAndUnavailableStatesDoNotBecomeConfirmedCachedSupport() {
        for (availability in listOf(SusfsAvailability.Unknown, SusfsAvailability.Unavailable)) {
            val cache = SusfsHomeCache.from(confirmed.copy(availability = availability))
            assertEquals(SusfsHomeCache(), cache)
            assertEquals(SusfsAvailability.Unknown, cache.restore(true).availability)
        }
    }

    @Test fun aRootOrStatusReadFailureRetainsTheConfirmedCache() {
        val restored = SusfsHomeCache.from(confirmed).restore(true)
        val failed = running.copy(
            susfs = running.susfs.queryFailed(BackendException("read", "failed")),
        )
        assertEquals(restored, restored.merge(SusfsHomeSnapshot.from(failed)))
        assertEquals(restored, restored.merge(SusfsHomeSnapshot.from(running.copy(unavailable = "No root"))))
        assertEquals(SusfsHomeCache.from(confirmed), SusfsHomeCache.from(restored))
    }

    @Test fun confirmedDisableDiscardsRuntimeCacheAndDoesNotResurrectOnUnknownRead() {
        val disabled = running.copy(susfs = running.susfs.copy(
            supported = false, loaded = false, autoload = false,
        ))
        val result = confirmed.merge(SusfsHomeSnapshot.from(disabled))
        assertEquals(SusfsHomeInfo(), result)
        assertEquals(SusfsHomeCache(), SusfsHomeCache.from(result))
        assertFalse(result.merge(SusfsHomeSnapshot.from(KpmUiState())).integrationEnabled)
    }

    @Test fun malformedCachedMetadataCannotBecomeVersionOrVariantText() {
        val result = SusfsHomeCache(
            available = true, version = "v2\nextra", variant = "x".repeat(129),
        ).restore(true)
        assertTrue(result.supported)
        assertNull(result.version)
        assertNull(result.variant)
        assertEquals("Supported | Unknown (Unknown)", result.summary("Supported", "Unavailable", "Unknown"))
    }

    @Test fun unconfirmedCacheCannotInventSupportFromVersionTextAlone() {
        val result = SusfsHomeCache(version = "v2.3.0", variant = "GKI-LKM").restore(true)
        assertEquals(SusfsAvailability.Unknown, result.availability)
        assertNull(result.version)
        assertNull(result.variant)
    }
}
