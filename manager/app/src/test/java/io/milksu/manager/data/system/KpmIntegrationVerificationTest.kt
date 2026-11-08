package io.milksu.manager.data.system

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.milksu.manager.ui.screen.kpm.KpmUiState
import org.junit.Assert.*
import org.junit.Test

class KpmIntegrationVerificationTest {
    private fun ok(data: String) = CommandOutput(0, """{"ok":true,"data":$data,"error":null}""", "")

    private fun status(engine: String = "old", verified: Boolean = true) =
        """{"supported":true,"configured_enabled":true,"active":true,"reboot_required":false,"can_enable":true,"can_disable":true,"integration_verified":$verified,"engine_sha256":"$engine"}"""

    private fun module(loaded: Boolean = false, autoload: Boolean = false) =
        """{"id":"a","name":"A","version":"1","author":"","description":"","args":"","sha256":"hash","autoload":$autoload,"loaded":$loaded,"pending_unload":false,"has_control":true}"""

    private fun emptyRepository() = KpmRepository { error("Verification must not use the module executor") }

    @Test fun recoveredIntegrationIsAcceptedAfterAnUnknownRuntimeSnapshot() = runBlocking {
        var recovered = false
        val repository = KpmRepository { args ->
            if (args[1] == "status") {
                ok(if (recovered) status(verified = false) else
                    """{"supported":true,"configured_enabled":false,"active":true,"reboot_required":false,"state_known":false,"integration_verified":false}""")
            } else ok("[]")
        }
        val initial = repository.refresh()
        val cold = KpmUiState(canPatchBoot = true).merge(initial)
        assertFalse(cold.canChangeIntegration)
        val checked = repository.verifyIntegration(CommandExecutor {
            assertEquals(listOf("kpm", "status"), it)
            recovered = true
            ok(status())
        })
        assertNotNull(checked)
        assertNull(checked!!.error)
        val fresh = cold.merge(repository.refresh()).mergeIntegrationVerification(checked)
        assertTrue(fresh.status!!.stateKnown)
        assertTrue(fresh.canSetIntegration(false))
    }

    @Test fun runtimeOnlyReplyCannotMasqueradeAsVerifiedIntegration() = runBlocking {
        val report = emptyRepository().verifyIntegration(CommandExecutor { ok(status(verified = false)) })
        assertEquals("unverified_integration", report!!.error?.code)
        assertNull(report.status)
    }

    @Test fun startupAndSettingsShareOneSuccessfulVerification() = runBlocking {
        var queries = 0
        val verifier = CommandExecutor {
            assertEquals(listOf("kpm", "status"), it)
            queries++
            ok(status())
        }
        val repository = emptyRepository()
        val startup = async { repository.verifyIntegration(verifier) }
        val settings = async { repository.verifyIntegration(verifier) }
        assertTrue(startup.await()!!.status!!.integrationVerified)
        assertTrue(settings.await()!!.status!!.integrationVerified)
        assertEquals(1, queries)
        assertFalse(repository.verifyIntegration(verifier)!!.statusQueried)
        assertEquals(1, queries)
    }

    @Test fun pendingVerificationDoesNotBlockImportOrInstallation() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var imported = false
            var loaded = false
            var autoload = false
            val calls = mutableListOf<List<String>>()
            val repository = KpmRepository { args ->
                calls += args
                when (args[1]) {
                    "status" -> {
                        assertEquals(listOf("kpm", "status", "--runtime-only"), args)
                        ok(status(verified = false))
                    }
                    "list" -> ok(if (imported) "[${module(loaded, autoload)}]" else "[]")
                    "import" -> { imported = true; ok(module()) }
                    "load" -> { loaded = true; ok(module(loaded)) }
                    "autoload" -> { autoload = args[3] == "enable"; ok(module(loaded, autoload)) }
                    else -> error("Unexpected command")
                }
            }
            val warmup = async {
                repository.verifyIntegration(CommandExecutor {
                    started.complete(Unit)
                    release.await()
                    ok(status())
                })
            }
            started.await()
            try {
                assertNull(repository.importModule("/private/a.kpm").report.error)
                val installed = repository.loadImported("a", "", temporary = false)
                assertNull(installed.error)
                assertTrue(installed.modules!!.single().loaded)
                assertTrue(installed.modules.single().autoload)
                assertFalse(warmup.isCompleted)
                assertEquals(2, calls.count { it == listOf("kpm", "status", "--runtime-only") })
            } finally {
                release.complete(Unit)
            }
            assertNotNull(warmup.await())
        }
    }

    @Test fun failedVerificationCanBeRetried() = runBlocking {
        var queries = 0
        val verifier = CommandExecutor {
            if (++queries == 1) {
                CommandOutput(1, """{"ok":false,"data":null,"error":{"code":"read_failed","message":"state unavailable"}}""", "")
            } else ok(status())
        }
        val repository = emptyRepository()
        assertEquals("read_failed", repository.verifyIntegration(verifier)!!.error?.code)
        assertNull(repository.verifyIntegration(verifier)!!.error)
        assertEquals(2, queries)
    }

    @Test fun expiredCacheIsRecheckedAndSlowExpiredResponsesAreDiscarded() = runBlocking {
        var time = 0L
        var queries = 0
        val repository = emptyRepository()
        val verifier = CommandExecutor { queries++; ok(status()) }
        assertNotNull(repository.verifyIntegration(verifier, nowNanos = { time }))
        time = 4L * 60 * 1_000_000_000
        assertNotNull(repository.verifyIntegration(verifier, nowNanos = { time }))
        assertEquals(1, queries)
        time = 6L * 60 * 1_000_000_000
        assertNotNull(repository.verifyIntegration(verifier, nowNanos = { time }))
        assertEquals(2, queries)

        val slow = emptyRepository()
        assertNull(slow.verifyIntegration(CommandExecutor {
            time += 6L * 60 * 1_000_000_000
            ok(status())
        }, nowNanos = { time }))
        assertNotNull(slow.verifyIntegration(verifier, nowNanos = { time }))
        assertEquals(3, queries)
    }

    @Test fun anIntegrationChangeDiscardsAnOlderInFlightVerification() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var verificationQueries = 0
            val repository = KpmRepository { args ->
                when (args[1]) {
                    "integration" -> ok("{}")
                    "status" -> ok(status("new"))
                    "list" -> ok("[]")
                    else -> error("Unexpected command")
                }
            }
            val verifier = CommandExecutor {
                verificationQueries++
                started.complete(Unit)
                release.await()
                ok(status())
            }
            val old = async { repository.verifyIntegration(verifier) }
            started.await()
            try {
                assertNull(repository.mutate(listOf("kpm", "integration", "enable")).error)
            } finally {
                release.complete(Unit)
            }
            assertNull(old.await())
            assertEquals("new", repository.verifyIntegration(verifier)!!.status!!.engineSha256)
            assertEquals(1, verificationQueries)
        }
    }

    @Test fun verificationDoesNotQueueBehindAnIntegrationChange() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = KpmRepository { args ->
                when (args[1]) {
                    "integration" -> { started.complete(Unit); release.await(); ok("{}") }
                    "status" -> ok(status("new"))
                    "list" -> ok("[]")
                    else -> error("Unexpected command")
                }
            }
            val change = async { repository.mutate(listOf("kpm", "integration", "enable")) }
            started.await()
            try {
                assertNull(repository.verifyIntegration(CommandExecutor {
                    error("A changing integration must not start verification")
                }))
            } finally {
                release.complete(Unit)
            }
            assertNull(change.await().error)
        }
    }

    @Test fun completedVerificationCannotBePublishedAfterAnIntegrationChange() = runBlocking {
        val repository = KpmRepository { args ->
            when (args[1]) {
                "status" -> ok(status("new"))
                "list" -> ok("[]")
                else -> ok("{}")
            }
        }
        val checked = repository.verifyIntegration(CommandExecutor { ok(status()) })!!
        assertTrue(repository.isCurrentVerification(checked))
        repository.mutate(listOf("kpm", "integration", "enable"))
        assertFalse(repository.isCurrentVerification(checked))
    }

    @Test fun newerRuntimeStateRejectsAnOlderInFlightVerification() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = KpmRepository { args ->
                if (args[1] == "status") ok(status("new", verified = false)) else ok("[]")
            }
            val old = async {
                repository.verifyIntegration(CommandExecutor {
                    started.complete(Unit)
                    release.await()
                    ok(status())
                })
            }
            started.await()
            try {
                assertEquals("new", repository.refresh().status!!.engineSha256)
            } finally {
                release.complete(Unit)
            }
            assertNull(old.await())
            assertEquals("new", repository.verifyIntegration(CommandExecutor { ok(status("new")) })!!.status!!.engineSha256)
        }
    }

    @Test fun backgroundVerificationCannotDelayNavigationOrAuthorizeFromACachedSnapshot() {
        val checked = KpmProtocol.decode<KpmStatus>(ok(status()))
        val cold = KpmUiState(canPatchBoot = true).mergeIntegrationVerification(KpmReport(status = checked))
        assertNull(cold.status)
        assertFalse(cold.navigationReady)
        assertFalse(cold.hasRead)
        assertFalse(cold.hasFreshStatus)
        assertFalse(cold.hasFreshIntegrationStatus)
        assertFalse(cold.canChangeIntegration)
        assertFalse(cold.busy)
        assertEquals(checked, cold.integrationVerification)
    }

    @Test fun verificationCannotOverwriteModulesPendingImportsOrCurrentRuntimeState() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status(verified = false)))
        val module = KpmProtocol.decode<KpmModule>(ok(module()))
        val ready = KpmUiState(canPatchBoot = true).merge(KpmReport(runtime, listOf(module))).copy(
            pendingImport = module, pendingImportIsNew = true,
        )
        val checked = runtime.copy(integrationVerified = true, canEnable = false)
        val warm = ready.mergeIntegrationVerification(KpmReport(status = checked, modules = emptyList()))
        assertSame(runtime, warm.status)
        assertEquals(ready.modules, warm.modules)
        assertEquals(module, warm.pendingImport)
        assertTrue(warm.pendingImportIsNew)
        assertTrue(warm.canLoad)
        assertFalse(warm.status!!.integrationVerified)
        assertFalse(warm.hasFreshIntegrationStatus)
        assertEquals(checked, warm.integrationVerification)
    }

    @Test fun verificationFailureDoesNotClearRuntimeFreshnessOrBlockImport() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status(verified = false)))
        val ready = KpmUiState(canPatchBoot = true).merge(KpmReport(runtime, emptyList()))
        val error = BackendException("verify_failed", "Cannot read the boot image")
        val failed = ready.mergeIntegrationVerification(KpmReport(error = error))
        assertNull(failed.error)
        assertSame(error, failed.integrationVerificationError)
        assertTrue(failed.hasFreshStatus)
        assertTrue(failed.canImport)
        assertTrue(failed.canLoad)
    }

    @Test fun matchingFailedEnablePreflightOverridesFastRequestFlagsWithoutChangingRuntimeFreshness() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status(verified = false))).copy(upgradeAvailable = true)
        val module = KpmProtocol.decode<KpmModule>(ok(module()))
        val ready = KpmUiState(canPatchBoot = true).merge(KpmReport(runtime, listOf(module)))
        assertTrue(ready.canUpgradeIntegration)
        val checked = runtime.copy(
            integrationVerified = true, canEnable = false, canDisable = true, canConfigure = true,
            reason = "Cannot verify the bundled KPM engine",
        )
        val result = ready.mergeIntegrationVerification(KpmReport(status = checked))
        assertTrue(result.showUpgradeIntegration)
        assertFalse(result.canUpgradeIntegration)
        assertFalse(result.canSetIntegration(true))
        assertTrue(result.canSetIntegration(false))
        assertEquals(checked.reason, result.integrationSettingsError)
        assertSame(runtime, result.status)
        assertEquals(ready.modules, result.modules)
        assertEquals(ready.navigationReady, result.navigationReady)
        assertEquals(ready.kpmTabEnabled, result.kpmTabEnabled)
        assertTrue(result.hasFreshStatus)
        assertTrue(result.canImport)
        assertTrue(result.canLoad)
        assertFalse(result.status!!.integrationVerified)
        assertFalse(result.merge(KpmReport(runtime, listOf(module))).canUpgradeIntegration)
    }

    @Test fun matchingVerificationSuppliesUpgradeAndDirectionsOnlyWhileRuntimeIsFresh() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status(verified = false))).copy(canEnable = false)
        val ready = KpmUiState(canPatchBoot = true).merge(KpmReport(runtime, emptyList()))
        val checked = runtime.copy(integrationVerified = true, canEnable = true, upgradeAvailable = true)
        val result = ready.mergeIntegrationVerification(KpmReport(status = checked))
        assertTrue(result.showUpgradeIntegration)
        assertTrue(result.canUpgradeIntegration)
        assertFalse(result.copy(hasFreshStatus = false).canUpgradeIntegration)
        assertFalse(result.copy(status = runtime.copy(stateKnown = false)).canUpgradeIntegration)
        assertSame(runtime, result.status)
        assertFalse(runtime.upgradeAvailable)
    }

    @Test fun failedOrExpiredVerificationCannotAuthorizeFromFastRequestFlags() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status(verified = false))).copy(upgradeAvailable = true)
        val snapshot = KpmReport(runtime, emptyList())
        val ready = KpmUiState(canPatchBoot = true).merge(snapshot)
        val failed = ready.mergeIntegrationVerification(KpmReport(
            error = BackendException("verify_failed", "Cannot read the boot image"),
        ))
        assertFalse(failed.canSetIntegration(true))
        assertFalse(failed.canSetIntegration(false))
        assertFalse(failed.merge(snapshot).canUpgradeIntegration)
        assertTrue(failed.canImport)

        val expired = ready.mergeIntegrationVerification(KpmReport(
            status = runtime.copy(integrationVerified = true),
            verificationCompletedAtNanos = System.nanoTime() - 6L * 60 * 1_000_000_000,
        ))
        assertFalse(expired.canSetIntegration(true))
        assertFalse(expired.canSetIntegration(false))
        assertFalse(expired.merge(snapshot).canUpgradeIntegration)
        assertTrue(expired.canImport)
        assertTrue(expired.canLoad)
    }

    @Test fun staleUnverifiedOrUnknownChecksCannotReplaceCurrentVerification() {
        val runtime = KpmProtocol.decode<KpmStatus>(ok(status("new", verified = false)))
        val ready = KpmUiState().merge(KpmReport(runtime, emptyList()))
        for (stale in listOf(
            runtime.copy(engineSha256 = "old", integrationVerified = true),
            runtime,
            runtime.copy(integrationVerified = true, stateKnown = false),
        )) {
            assertSame(ready, ready.mergeIntegrationVerification(KpmReport(status = stale)))
        }
    }
}
