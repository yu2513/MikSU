package io.milksu.manager.data.system

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class KpmRepositoryTest {
    private val status = """{"supported":true,"configured_enabled":false,"active":false,"reboot_required":false}"""
    private fun ok(data: String) = CommandOutput(0, """{"ok":true,"data":$data,"error":null}""", "")
    private val activeStatus = """{"supported":true,"configured_enabled":true,"active":true,"reboot_required":false}"""
    private fun module(loaded: Boolean = false, autoload: Boolean = false, pending: Boolean = false) =
        """{"id":"a","name":"A","version":"1","author":"","description":"","args":"literal args","sha256":"hash","autoload":$autoload,"loaded":$loaded,"pending_unload":$pending,"has_control":true}"""
    private fun failed() = CommandOutput(1,
        """{"ok":false,"data":null,"error":{"code":"kernel_error","message":"failed"}}""", "")

    @Test fun importOnlyRegistersAndRequeriesWithoutBootVerification() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val repository = KpmRepository { args ->
            calls += args
            when (args[1]) { "status" -> ok(status); "list" -> ok("[${module()}]"); else -> ok(module()) }
        }
        val report = repository.mutate(listOf("kpm", "import", "/private/a.kpm"))
        assertNull(report.error)
        assertEquals("a", report.detail?.id)
        assertFalse(report.detail!!.loaded)
        assertEquals(listOf("import", "list"), calls.map { it[1] })
        assertNull(report.status)
        assertFalse(report.statusQueried)
        assertFalse(calls.any { it.contains("load") || it.contains("autoload") })
    }

    @Test fun documentImportChecksRegistryAndNewnessWithoutAnyStatusQuery() = runBlocking {
        for (alreadyRegistered in listOf(false, true)) {
            val calls = mutableListOf<List<String>>()
            var imported = alreadyRegistered
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "list" -> ok(if (imported) "[${module()}]" else "[]")
                    "import" -> { imported = true; ok(module()) }
                    else -> error("Import must not inspect the boot image")
                }
            }
            val result = repository.importModule("/private/a.kpm")
            assertNull(result.report.error)
            assertEquals(!alreadyRegistered, result.isNew)
            assertEquals("a", result.report.detail?.id)
            assertEquals(listOf(
                listOf("kpm", "list"),
                listOf("kpm", "import", "/private/a.kpm"),
                listOf("kpm", "list"),
            ), calls)
        }
    }

    @Test fun unreadableRegistryDoesNotStartDocumentImport() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val repository = KpmRepository { args ->
            calls += args
            failed()
        }
        val result = repository.importModule("/private/a.kpm")
        assertEquals("kernel_error", result.report.error?.code)
        assertFalse(result.isNew)
        assertNull(result.report.detail)
        assertEquals(listOf(listOf("kpm", "list")), calls)
    }

    @Test fun refreshUsesRuntimeStatusUnlessFullVerificationIsRequested() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val repository = KpmRepository { args ->
            calls += args
            when (args[1]) { "status" -> ok(status); "list" -> ok("[]"); else -> error("Unexpected command") }
        }
        val runtime = repository.refresh()
        assertFalse(runtime.status!!.integrationVerified)
        assertTrue(runtime.statusQueried)
        assertEquals(listOf(listOf("kpm", "status", "--runtime-only"), listOf("kpm", "list")), calls)
        calls.clear()
        val full = repository.refresh(runtimeOnly = false)
        assertTrue(full.status!!.integrationVerified)
        assertEquals(listOf(listOf("kpm", "status"), listOf("kpm", "list")), calls)
    }

    @Test fun mutationFailureStillRequeriesAndPreservesOriginalError() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = KpmRepository { args ->
            calls += args[1]
            when (args[1]) {
                "status" -> ok(status)
                "list" -> ok("[]")
                else -> CommandOutput(4, """{"ok":false,"data":null,"error":{"code":"busy","message":"loaded"}}""", "busy")
            }
        }
        val report = repository.mutate(listOf("kpm", "remove", "test"))
        assertEquals(listOf("remove", "status", "list"), calls)
        assertEquals("busy", report.error?.code)
        assertNotNull(report.status)
        assertNotNull(report.modules)
    }

    @Test fun removalIsOnlySuccessfulAfterTheModuleDisappearsFromTheRegistry() = runBlocking {
        for (removed in listOf(false, true)) {
            val calls = mutableListOf<List<String>>()
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "remove" -> ok("""{"id":"a","removed":true}""")
                    "status" -> ok(activeStatus)
                    "list" -> ok(if (removed) "[]" else "[${module(loaded = true)}]")
                    else -> error("The daemon handles unload and removal in one command")
                }
            }
            val report = repository.mutate(listOf("kpm", "remove", "a"))
            assertEquals(if (removed) null else "unconfirmed_remove", report.error?.code)
            assertEquals(listOf(
                listOf("kpm", "remove", "a"),
                listOf("kpm", "status", "--runtime-only"),
                listOf("kpm", "list"),
            ), calls)
        }
    }

    @Test fun failedUnloadPreservesTheActualErrorAndRegisteredModuleForRetry() = runBlocking {
        val message = "Update KPM integration in Settings and reboot to enable unloading"
        val repository = KpmRepository { args ->
            when (args[1]) {
                "remove" -> CommandOutput(1,
                    """{"ok":false,"data":null,"error":{"code":"kernel_update_required","message":"$message"}}""", "")
                "status" -> ok(activeStatus)
                "list" -> ok("[${module(loaded = true, autoload = true)}]")
                else -> error("The manager must not remove the file after unload fails")
            }
        }
        val report = repository.mutate(listOf("kpm", "remove", "a"))
        assertEquals("kernel_update_required", report.error?.code)
        assertEquals(message, report.error?.message)
        assertEquals("a", report.modules!!.single().id)
        assertTrue(report.modules.single().loaded)
    }

    @Test fun integrationChangesUseRequeriedStateInsteadOfMutationReply() = runBlocking {
        for (enabled in listOf(true, false)) {
            val calls = mutableListOf<List<String>>()
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "status" -> ok("""{"supported":true,"configured_enabled":$enabled,"active":${!enabled},"reboot_required":true}""")
                    "list" -> ok("[]")
                    else -> ok("""{"configured_enabled":${!enabled},"reboot_required":false}""")
                }
            }
            val action = if (enabled) "enable" else "disable"
            val report = repository.mutate(listOf("kpm", "integration", action))
            assertEquals(listOf(
                listOf("kpm", "integration", action), listOf("kpm", "status"), listOf("kpm", "list"),
            ), calls)
            assertNull(report.error)
            assertEquals(enabled, report.status!!.configuredEnabled)
            assertEquals(!enabled, report.status.active)
            assertTrue(report.status.rebootRequired)
        }
    }

    @Test fun failedIntegrationStillReturnsActualStateAfterPartialChange() = runBlocking {
        for (enabled in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val repository = KpmRepository { args ->
                calls += args[1]
                when (args[1]) {
                    "status" -> ok("""{"supported":true,"configured_enabled":$enabled,"active":${!enabled},"reboot_required":true}""")
                    "list" -> ok("[]")
                    else -> CommandOutput(1,
                        """{"ok":false,"data":null,"error":{"code":"operation_failed","message":"verification failed"}}""", "trace")
                }
            }
            val report = repository.mutate(listOf("kpm", "integration", if (enabled) "enable" else "disable"))
            assertEquals(listOf("integration", "status", "list"), calls)
            assertEquals("operation_failed", report.error?.code)
            assertEquals("trace", report.error?.stderr)
            assertEquals(enabled, report.status!!.configuredEnabled)
            assertEquals(!enabled, report.status.active)
            assertTrue(report.status.rebootRequired)
            assertNotNull(report.modules)
        }
    }

    @Test fun upgradeRebootGateUsesFullRequeriedStateInsteadOfTheEnableReply() = runBlocking {
        for (staged in listOf(false, true)) {
            val calls = mutableListOf<List<String>>()
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "integration" -> ok("""{"configured_enabled":true,"reboot_required":true,"upgrade_available":false}""")
                    "status" -> ok("""{"supported":true,"configured_enabled":true,"active":true,"reboot_required":$staged,"upgrade_available":${!staged},"integration_verified":true}""")
                    "list" -> ok("[]")
                    else -> error("Unexpected command")
                }
            }
            val report = repository.mutate(listOf("kpm", "integration", "enable"))
            assertNull(report.error)
            assertEquals(staged, report.verifiesIntegrationUpgrade)
            assertEquals(listOf(
                listOf("kpm", "integration", "enable"), listOf("kpm", "status"), listOf("kpm", "list"),
            ), calls)
        }
    }

    @Test fun concurrentRequestsDoNotOverlapCommands() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val repository = KpmRepository { args ->
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            delay(5)
            active.decrementAndGet()
            if (args[1] == "status") ok(status) else ok("[]")
        }
        val first = async { repository.refresh() }
        val second = async { repository.refresh() }
        first.await()
        second.await()
        assertEquals(1, peak.get())
    }

    @Test fun failedListIsNullSoCallerCanKeepExistingList() = runBlocking {
        val repository = KpmRepository { args -> if (args[1] == "status") ok(status) else CommandOutput(1, "", "read failed") }
        val result = repository.refresh()
        assertNotNull(result.status)
        assertNull(result.modules)
        assertNotNull(result.error)
        assertTrue(result.statusQueried)
    }

    @Test fun failedStatusAndFailedDetailsHaveSeparateFreshness() = runBlocking {
        val repository = KpmRepository { args ->
            if (args[1] == "list") ok("[]") else CommandOutput(1, "", "read failed")
        }
        assertTrue(repository.refresh().statusQueried)
        assertFalse(repository.info("missing").statusQueried)
    }

    @Test fun controlReturnsNativeOutputWithoutLosingRefreshedState() = runBlocking {
        val repository = KpmRepository { args ->
            when (args[1]) {
                "status" -> ok(status)
                "list" -> ok("[]")
                else -> ok("""{"id":"test","output":"native reply"}""")
            }
        }
        val result = repository.mutate(listOf("kpm", "control", "test", "--args", "value with spaces"))
        assertEquals("native reply", result.output)
        assertNotNull(result.status)
        assertNotNull(result.modules)
    }

    @Test fun duplicateIdsDoNotReplaceTheCachedList() = runBlocking {
        val module = """{"id":"a","name":"A","version":"1","author":"","description":"","args":"","sha256":"","autoload":false,"loaded":false,"pending_unload":false,"has_control":false}"""
        val repository = KpmRepository { args -> if (args[1] == "status") ok(status) else ok("[$module,$module]") }
        val result = repository.refresh()
        assertNull(result.modules)
        assertEquals("invalid_data", result.error?.code)
    }

    @Test fun malformedImportReplyCannotOpenConfirmation() = runBlocking {
        val repository = KpmRepository { args ->
            when (args[1]) { "status" -> ok(status); "list" -> ok("[]"); else -> ok("null") }
        }
        val report = repository.mutate(listOf("kpm", "import", "/private/a.kpm"))
        assertEquals("invalid_data", report.error?.code)
        assertNull(report.detail)
    }

    @Test fun duplicateResidentImportReportsErrorAndNeverMutatesExistingModule() = runBlocking {
        for (residentInReply in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val repository = KpmRepository { args ->
                calls += args[1]
                when (args[1]) {
                    "import" -> ok(module(loaded = residentInReply))
                    "status" -> ok(activeStatus)
                    "list" -> ok("[${module(loaded = true)}]")
                    else -> error("Unexpected mutation")
                }
            }
            val report = repository.mutate(listOf("kpm", "import", "/private/a.kpm"))
            assertEquals("already_loaded", report.error?.code)
            assertEquals("a", report.detail?.id)
            assertTrue(report.modules!!.single().loaded)
            assertEquals(listOf("import", "list"), calls)
        }
    }

    @Test fun importedLoadSetsAutoloadOnlyAfterConfirmedLoad() = runBlocking {
        for (temporary in listOf(false, true)) {
            val calls = mutableListOf<List<String>>()
            var loaded = false
            var autoload = false
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "status" -> ok(activeStatus)
                    "list" -> ok("[${module(loaded, autoload)}]")
                    "load" -> { loaded = true; ok(module(loaded, autoload)) }
                    "autoload" -> { autoload = args[3] == "enable"; ok(module(loaded, autoload)) }
                    else -> error("Unexpected command")
                }
            }
            val report = repository.loadImported("a", "literal args", temporary)
            assertNull(report.error)
            assertEquals(listOf("status", "list", "load", "autoload", "status", "list"), calls.map { it[1] })
            assertEquals(2, calls.count { it == listOf("kpm", "status", "--runtime-only") })
            assertEquals(listOf("kpm", "load", "a", "--args", "literal args"), calls[2])
            assertEquals(if (temporary) "disable" else "enable", calls[3][3])
            assertEquals(!temporary, report.modules!!.single().autoload)
            assertTrue(report.modules.single().loaded)
        }
    }

    @Test fun failedLoadNeverChangesAutoload() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = KpmRepository { args ->
            calls += args[1]
            when (args[1]) { "status" -> ok(activeStatus); "list" -> ok("[${module()}]"); else -> failed() }
        }
        val report = repository.loadImported("a", "", false)
        assertEquals("kernel_error", report.error?.code)
        assertFalse(calls.contains("autoload"))
        assertFalse(report.modules!!.single().loaded)
    }

    @Test fun autoloadFailurePreservesLoadedStateAndRetryDoesNotLoadAgain() = runBlocking {
        val calls = mutableListOf<String>()
        var loaded = false
        var failAutoload = true
        var autoload = false
        val repository = KpmRepository { args ->
            calls += args[1]
            when (args[1]) {
                "status" -> ok(activeStatus)
                "list" -> ok("[${module(loaded, autoload)}]")
                "load" -> { loaded = true; ok(module(true)) }
                "autoload" -> if (failAutoload) failed() else { autoload = true; ok(module(true, true)) }
                else -> error("Unexpected command")
            }
        }
        val failed = repository.loadImported("a", "", false)
        assertEquals("kernel_error", failed.error?.code)
        assertTrue(failed.modules!!.single().loaded)
        assertFalse(failed.modules.single().autoload)
        failAutoload = false
        val retry = repository.loadImported("a", "", false)
        assertNull(retry.error)
        assertTrue(retry.modules!!.single().autoload)
        assertEquals(1, calls.count { it == "load" })
    }

    @Test fun inactiveEngineAndPendingUnloadNeverLoad() = runBlocking {
        for (pending in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val repository = KpmRepository { args ->
                calls += args[1]
                when (args[1]) {
                    "status" -> ok(if (pending) activeStatus else status)
                    "list" -> ok("[${module(pending = pending)}]")
                    else -> error("Unexpected mutation")
                }
            }
            val report = repository.loadImported("a", "", false)
            assertEquals(if (pending) "reboot_required" else "engine_inactive", report.error?.code)
            assertEquals(listOf("status", "list"), calls)
        }
    }

    @Test fun unknownIntegrationStateNeverLoadsEvenWithAnActiveEngine() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val repository = KpmRepository { args ->
            calls += args
            when (args[1]) {
                "status" -> ok("""{"supported":true,"configured_enabled":true,"active":true,"reboot_required":false,"state_known":false,"integration_verified":false}""")
                "list" -> ok("[${module()}]")
                else -> error("Unknown integration state must not load")
            }
        }
        val report = repository.loadImported("a", "", false)
        assertEquals("engine_inactive", report.error?.code)
        assertEquals(listOf(listOf("kpm", "status", "--runtime-only"), listOf("kpm", "list")), calls)
    }

    @Test fun mismatchedOrPendingLoadReplyNeverEnablesAutoloadAndStillRequeries() = runBlocking {
        for (differentId in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val repository = KpmRepository { args ->
                calls += args[1]
                when (args[1]) {
                    "status" -> ok(activeStatus)
                    "list" -> ok("[${module()}]")
                    "load" -> ok(if (differentId) module(loaded = true).replace("\"a\"", "\"other\"") else module(loaded = true, pending = true))
                    else -> error("Unconfirmed module must not change autoload")
                }
            }
            val report = repository.loadImported("a", "", false)
            assertEquals(if (differentId) "invalid_data" else "unconfirmed_load", report.error?.code)
            assertEquals(listOf("status", "list", "load", "status", "list"), calls)
        }
    }

    @Test fun failedFinalRuntimeQueryDoesNotReportAConfirmedLoad() = runBlocking {
        var queries = 0
        var loaded = false
        val repository = KpmRepository { args ->
            when (args[1]) {
                "status" -> if (queries++ == 0) ok(activeStatus) else failed()
                "list" -> ok("[${module(loaded, loaded)}]")
                "load" -> { loaded = true; ok(module(loaded)) }
                "autoload" -> ok(module(true, true))
                else -> error("Unexpected command")
            }
        }
        val report = repository.loadImported("a", "", false)
        assertEquals("kernel_error", report.error?.code)
        assertNull(report.status)
        assertTrue(report.modules!!.single().loaded)
        assertTrue(report.statusQueried)
    }

    @Test fun unconfirmedLoadOrAutoloadDoesNotReportSuccess() = runBlocking {
        for (loadConfirmed in listOf(false, true)) {
            var loaded = false
            val calls = mutableListOf<String>()
            val repository = KpmRepository { args ->
                calls += args[1]
                when (args[1]) {
                    "status" -> ok(activeStatus)
                    "list" -> ok("[${module(loaded)}]")
                    "load" -> { loaded = loadConfirmed; ok(module(loaded)) }
                    "autoload" -> ok(module(loaded))
                    else -> error("Unexpected command")
                }
            }
            val report = repository.loadImported("a", "", false)
            assertEquals(if (loadConfirmed) "unconfirmed_autoload" else "unconfirmed_load", report.error?.code)
            assertEquals(loadConfirmed, calls.contains("autoload"))
        }
    }
}
