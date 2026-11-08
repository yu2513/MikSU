package io.milksu.manager.data.system

import kotlinx.coroutines.runBlocking
import io.milksu.manager.ui.screen.kpm.KpmUiState
import io.milksu.manager.ui.screen.kpm.SusfsState
import org.junit.Assert.*
import org.junit.Test

class SusfsProtocolTest {
    @Test fun residentGuardRemainsEnabledUntilItsUnloadIsConfirmed() {
        val state = KpmUiState(susfs = SusfsState(
            loaded = true, supported = true, builtin = false, autoload = false,
        ))
        assertTrue(state.susfsIntegrationEnabled)
        assertTrue(state.canChangeSusfsIntegration)
        assertFalse(state.copy(susfs = state.susfs.copy(loaded = false)).susfsIntegrationEnabled)
        assertFalse(state.copy(susfs = state.susfs.copy(builtin = true)).susfsIntegrationEnabled)
    }

    private fun runtimeStatus(loaded: Boolean, autoload: Boolean = false, supported: Boolean = loaded) =
        CommandOutput(0, """{"supported":$supported,"loaded":$loaded,"builtin":false,"autoload":$autoload}""", "")

    @Test fun disablingIntegrationStopsTheGuardAndDisablesAutoload() = runBlocking {
        val calls = mutableListOf<String>()
        var loaded = true
        var autoload = true
        val executor = CommandExecutor { args ->
            calls += args[1]
            when (args[1]) {
                "autoload" -> { autoload = false; CommandOutput(0, "", "") }
                "unload" -> { loaded = false; CommandOutput(0, "", "") }
                else -> runtimeStatus(loaded, autoload)
            }
        }
        val result = SusfsProtocol.disableIntegration(executor)
        assertFalse(result.loaded)
        assertFalse(result.autoload)
        assertEquals(listOf("autoload", "status", "unload", "status"), calls)
    }

    @Test fun disablingAnAlreadyStoppedGuardDoesNotUnloadItAgain() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = CommandExecutor { args ->
            calls += args[1]
            if (args[1] == "status") runtimeStatus(false) else CommandOutput(0, "", "")
        }
        assertFalse(SusfsProtocol.disableIntegration(executor).autoload)
        assertEquals(listOf("autoload", "status"), calls)
    }

    @Test fun enablingAutoloadStartsSusfsInTheCurrentBootBeforeSaving() = runBlocking {
        val calls = mutableListOf<List<String>>()
        var loaded = false
        var autoload = false
        val executor = CommandExecutor { args ->
            calls += args
            when (args[1]) {
                "status" -> runtimeStatus(loaded, autoload)
                "load" -> { loaded = true; CommandOutput(0, "", "") }
                "autoload" -> {
                    assertTrue(loaded)
                    autoload = true
                    CommandOutput(0, "", "")
                }
                else -> error("Unexpected command")
            }
        }
        val confirmed = SusfsProtocol.enableAutoload(executor, "android14-6.1")
        assertTrue(confirmed.loaded)
        assertTrue(confirmed.autoload)
        assertEquals(listOf("status", "load", "status", "autoload", "status"), calls.map { it[1] })
        assertEquals(listOf("susfs", "load", "--kmi", "android14-6.1"), calls[1])
    }

    @Test fun alreadyRunningSusfsIsNotLoadedAgain() = runBlocking {
        val calls = mutableListOf<String>()
        var autoload = false
        val executor = CommandExecutor { args ->
            calls += args[1]
            if (args[1] == "status") runtimeStatus(true, autoload)
            else {
                assertEquals("autoload", args[1])
                autoload = true
                CommandOutput(0, "", "")
            }
        }
        assertTrue(SusfsProtocol.enableAutoload(executor, "android14-6.1").autoload)
        assertEquals(listOf("status", "autoload", "status"), calls)
    }

    @Test fun failedImmediateLoadDoesNotEnableAutoload() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = CommandExecutor { args ->
            calls += args[1]
            if (args[1] == "status") runtimeStatus(false)
            else CommandOutput(1, "", "load failed")
        }
        try {
            SusfsProtocol.enableAutoload(executor, "android14-6.1")
            fail("A failed load must not enable autoload")
        } catch (error: BackendException) {
            assertEquals("susfs_command", error.code)
        }
        assertEquals(listOf("status", "load"), calls)
    }

    @Test fun successfulLoadCommandStillRequiresAnOperationalGuard() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = CommandExecutor { args ->
            calls += args[1]
            if (args[1] == "status") runtimeStatus(calls.size > 1, supported = false)
            else CommandOutput(0, "", "")
        }
        try {
            SusfsProtocol.enableAutoload(executor, "android14-6.1")
            fail("A nonoperational guard must not enable autoload")
        } catch (error: BackendException) {
            assertEquals("susfs_not_running", error.code)
        }
        assertFalse(calls.contains("autoload"))
    }

    @Test fun readsDirectStatusJsonWithoutEnvelope() {
        val status = SusfsProtocol.decodeStatus(CommandOutput(0,
            """{"supported":true,"loaded":false,"builtin":true,"autoload":false,"version":"1","kmi":null,"reason":null}""", ""))
        assertTrue(status.builtin)
        assertFalse(status.autoload)
        assertNull(status.kmi)
    }

    @Test fun plainMutationOutputUsesExitStatus() {
        assertEquals(0, SusfsProtocol.checkCommand(CommandOutput(0, "SUSFS loaded", "")).exitCode)
        val error = assertThrows(BackendException::class.java) {
            SusfsProtocol.checkCommand(CommandOutput(3, "", "module is busy"))
        }
        assertTrue(error.display.contains("module is busy"))
    }

    @Test fun failedOrMalformedStatusIsNotAnOffState() {
        assertThrows(BackendException::class.java) {
            SusfsProtocol.decodeStatus(CommandOutput(0, """{"autoload":false}""", ""))
        }
        assertThrows(BackendException::class.java) {
            SusfsProtocol.decodeStatus(CommandOutput(1, "{}", "cannot query"))
        }
    }

    @Test fun residentFailedGuardIsNotMutableEvenWhenStatusQuerySucceeds() {
        val status = SusfsProtocol.decodeStatus(CommandOutput(0,
            """{"supported":false,"loaded":true,"builtin":false,"autoload":true,"reason":"Guard is resident but not operational; failed restoration requires reboot"}""", ""))
        val state = KpmUiState(susfs = SusfsState(
            supported = status.supported, loaded = status.loaded, builtin = status.builtin,
            autoload = status.autoload, reason = status.reason,
        ))
        assertTrue(state.susfs.loaded!!)
        assertTrue(state.susfs.autoload!!)
        assertNull(state.susfs.error)
        assertFalse(state.canMutateSusfs)
        assertTrue(state.canChangeSusfsAutoload)
        assertTrue(state.copy(safeMode = true).canChangeSusfsAutoload)
        assertFalse(state.copy(susfs = state.susfs.copy(autoload = false)).canChangeSusfsAutoload)
        assertTrue(state.susfs.reason!!.contains("reboot"))
    }

    @Test fun autoloadCannotChangeWithoutKnownReadableStatus() {
        val known = SusfsState(supported = false, loaded = true, builtin = false, autoload = true)
        val state = KpmUiState(susfs = known)
        assertTrue(state.canChangeSusfsAutoload)
        assertFalse(state.copy(busy = true).canChangeSusfsAutoload)
        assertFalse(state.copy(unavailable = "No root").canChangeSusfsAutoload)
        for (unknown in listOf(
            known.copy(supported = null), known.copy(loaded = null), known.copy(builtin = null),
            known.copy(autoload = null), known.queryFailed(BackendException("query", "failed")),
        )) {
            assertFalse(state.copy(susfs = unknown).canChangeSusfsAutoload)
        }
    }

    @Test fun enablingAutoloadRetainsSafeModeBuiltinAndRuntimeGates() {
        val state = KpmUiState(susfs = SusfsState(
            supported = true, loaded = true, builtin = false, autoload = false,
        ))
        assertTrue(state.canChangeSusfsAutoload)
        assertTrue(state.canMutateSusfs)
        assertFalse(state.copy(safeMode = true).canChangeSusfsAutoload)
        assertFalse(state.copy(susfs = state.susfs.copy(builtin = true)).canChangeSusfsAutoload)
        assertFalse(state.copy(susfs = state.susfs.copy(supported = false)).canChangeSusfsAutoload)
        val builtin = state.copy(safeMode = true, susfs = state.susfs.copy(builtin = true, autoload = true))
        assertTrue(builtin.canChangeSusfsAutoload)
    }

    @Test fun anUnloadedGuardDoesNotMakeIntegrationUnavailable() {
        val state = KpmUiState(susfs = SusfsState().merge(SusfsStatus(
            supported = false, loaded = false, builtin = false, autoload = false,
            kmi = "android14-6.1", reason = "SUSFS is not loaded",
        )))
        assertTrue(state.canChangeSusfsIntegration)
        assertFalse(state.susfsIntegrationEnabled)
        assertFalse(state.copy(safeMode = true).canChangeSusfsIntegration)
    }

    @Test fun actionFailureDoesNotDisableAConfirmedRunningGuard() {
        val running = SusfsState().merge(SusfsStatus(
            supported = true, loaded = true, builtin = false, autoload = true,
        ))
        val failed = running.copy(error = BackendException("susfs_command", "Temporary failure"))
        val state = KpmUiState(susfs = failed)
        assertTrue(state.susfsIntegrationEnabled)
        assertTrue(state.canChangeSusfsIntegration)
        assertTrue(state.copy(safeMode = true).canChangeSusfsIntegration)
        assertFalse(state.copy(busy = true).canChangeSusfsIntegration)
        assertFalse(state.copy(unavailable = "No root").canChangeSusfsIntegration)
    }

    @Test fun failedImmediateLoadCanBeRetriedWithoutRestartingTheApp() {
        val absent = SusfsState().merge(SusfsStatus(
            supported = false, loaded = false, builtin = false, autoload = false,
        ))
        val state = KpmUiState(susfs = absent.copy(
            error = BackendException("susfs_command", "Load failed"),
        ))
        assertTrue(state.canChangeSusfsIntegration)
        assertFalse(state.susfsIntegrationEnabled)
    }

    @Test fun queryFailureStillBlocksMutationsUntilAReadableStatusArrives() {
        val status = SusfsStatus(supported = true, loaded = true, builtin = false, autoload = true)
        val running = SusfsState().merge(status)
        val failed = running.queryFailed(BackendException("query", "Permission denied"))
            .copy(error = BackendException("susfs_command", "Unload failed"))
        val state = KpmUiState(susfs = failed)
        assertTrue(state.susfsIntegrationEnabled)
        assertFalse(state.canChangeSusfsIntegration)
        val refreshed = state.copy(susfs = failed.merge(status))
        assertTrue(refreshed.canChangeSusfsIntegration)
        assertNull(refreshed.susfs.error)
        assertNull(refreshed.susfs.queryError)
    }

    @Test fun reasonTextIsNotAnAvailabilityFlag() {
        val state = KpmUiState(susfs = SusfsState().merge(SusfsStatus(
            supported = true, loaded = true, builtin = false, autoload = true,
            reason = "Cannot read SUSFS auto-load config: previous attempt",
        )))
        assertTrue(state.canChangeSusfsIntegration)
    }

    @Test fun aPreparedGuardCanRetryCleanupWithoutEnablingItAgain() {
        val prepared = SusfsState().merge(SusfsStatus(
            supported = false, loaded = true, builtin = false, autoload = false,
            reason = "Guard is prepared but delete_module failed",
        ))
        val state = KpmUiState(susfs = prepared.copy(
            error = BackendException("susfs_command", "Module is still busy"),
        ))
        assertTrue(state.susfsIntegrationEnabled)
        assertTrue(state.canChangeSusfsIntegration)
        assertFalse(state.canMutateSusfs)
        assertFalse(state.canChangeSusfsAutoload)
        assertFalse(state.copy(susfs = state.susfs.copy(builtin = true)).canChangeSusfsIntegration)
    }

    @Test fun aFailedUnloadCanBeRetriedAfterAutoloadWasAlreadyDisabled() = runBlocking {
        var loaded = true
        var autoload = true
        var unloadAttempts = 0
        val executor = CommandExecutor { args ->
            when (args[1]) {
                "autoload" -> { autoload = false; CommandOutput(0, "", "") }
                "unload" -> {
                    unloadAttempts++
                    if (unloadAttempts == 1) CommandOutput(1, "", "Module is still busy")
                    else { loaded = false; CommandOutput(0, "", "") }
                }
                else -> runtimeStatus(loaded, autoload, supported = false)
            }
        }
        try {
            SusfsProtocol.disableIntegration(executor)
            fail("The first unload must report its failure")
        } catch (error: BackendException) {
            val state = KpmUiState(susfs = SusfsState().merge(
                SusfsProtocol.decodeStatus(executor.execute(listOf("susfs", "status")))
            ).copy(error = error))
            assertTrue(state.susfsIntegrationEnabled)
            assertTrue(state.canChangeSusfsIntegration)
        }
        val stopped = SusfsProtocol.disableIntegration(executor)
        assertFalse(stopped.loaded)
        assertFalse(stopped.autoload)
        assertEquals(2, unloadAttempts)
    }
}
