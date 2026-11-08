package io.milksu.manager.data.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

internal const val KPM_VERIFICATION_TTL_NANOS = 5L * 60 * 1_000_000_000

fun interface CommandExecutor {
    suspend fun execute(arguments: List<String>): CommandOutput
}

data class KpmReport(
    val status: KpmStatus? = null,
    val modules: List<KpmModule>? = null,
    val detail: KpmModule? = null,
    val error: BackendException? = null,
    val output: String? = null,
    val statusQueried: Boolean = false,
    val verificationRevision: Long? = null,
    val verificationCompletedAtNanos: Long? = null,
) {
    val verifiesIntegrationUpgrade: Boolean
        get() = error == null && status?.let {
            it.integrationVerified && it.stateKnown && it.configuredEnabled &&
                it.rebootRequired && !it.transitionFailed && !it.upgradeAvailable
        } == true
}

data class KpmImportResult(val report: KpmReport, val isNew: Boolean)

class KpmRepository(private val executor: CommandExecutor) {
    private val mutex = Mutex()
    private val verificationMutex = Mutex()
    private val integrationRevision = AtomicLong()
    private var latestStatus: KpmStatus? = null
    private var verification: IntegrationVerification? = null

    private data class IntegrationVerification(
        val revision: Long,
        val completedAt: Long,
        val status: KpmStatus,
    )

    fun isCurrentVerification(report: KpmReport): Boolean =
        report.verificationRevision?.let { it % 2L == 0L && it == integrationRevision.get() } == true

    suspend fun verifyIntegration(
        verifier: CommandExecutor = executor,
        nowNanos: () -> Long = System::nanoTime,
    ): KpmReport? = verificationMutex.withLock {
        val startedAt = nowNanos()
        val revision = integrationRevision.get()
        if (revision % 2L != 0L) return@withLock null
        val cached = mutex.withLock {
            verification?.takeIf {
                it.revision == revision && startedAt - it.completedAt in 0L until KPM_VERIFICATION_TTL_NANOS
            }
        }
        if (revision != integrationRevision.get()) return@withLock null
        if (cached != null) return@withLock KpmReport(
            status = cached.status,
            verificationRevision = revision,
            verificationCompletedAtNanos = cached.completedAt,
        )

        // This runs outside the module-command lock and on a separate root shell.
        val report = try {
            val status = KpmProtocol.decode<KpmStatus>(verifier.execute(listOf("kpm", "status")))
            if (!status.integrationVerified || !status.stateKnown) {
                throw BackendException("unverified_integration", status.reason ?: "Cannot verify KPM integration state")
            }
            KpmReport(status = status, statusQueried = true, verificationRevision = revision)
        } catch (e: Exception) {
            KpmReport(error = e.backendError(), statusQueried = true, verificationRevision = revision)
        }
        val completedAt = nowNanos()
        mutex.withLock {
            if (revision != integrationRevision.get() ||
                completedAt - startedAt !in 0L until KPM_VERIFICATION_TTL_NANOS ||
                (report.status != null && latestStatus?.stateKnown == true &&
                    latestStatus?.sameIntegrationState(report.status) == false)
            ) {
                null
            } else {
                verification = report.status?.let { IntegrationVerification(revision, completedAt, it) }
                report.copy(verificationCompletedAtNanos = completedAt)
            }
        }
    }

    suspend fun refresh(runtimeOnly: Boolean = true): KpmReport = mutex.withLock {
        refreshUnlocked(runtimeOnly)
    }

    suspend fun list(): KpmReport = mutex.withLock { listUnlocked() }

    suspend fun importModule(path: String): KpmImportResult = mutex.withLock {
        val before = listUnlocked()
        if (before.error != null) return@withLock KpmImportResult(before, false)
        val existingIds = before.modules.orEmpty().map { it.id }.toSet()
        val report = mutateUnlocked(listOf("kpm", "import", path))
        KpmImportResult(report, report.detail?.id?.let { it !in existingIds } == true)
    }

    suspend fun info(id: String): KpmReport = mutex.withLock {
        try {
            KpmReport(detail = KpmProtocol.decode(executor.execute(listOf("kpm", "info", id))))
        } catch (e: Exception) {
            KpmReport(error = e.backendError())
        }
    }

    suspend fun mutate(arguments: List<String>): KpmReport = mutex.withLock {
        mutateUnlocked(arguments)
    }

    suspend fun loadImported(id: String, args: String, temporary: Boolean): KpmReport = mutex.withLock {
        val report = refreshUnlocked()
        if (report.error != null) return@withLock report
        val status = report.status
        if (status == null || !status.stateKnown || !status.supported || !status.active || !status.configuredEnabled ||
            status.rebootRequired || status.transitionFailed
        ) {
            return@withLock report.copy(error = BackendException("engine_inactive", "KPM integration is not active"))
        }
        val module = report.modules?.find { it.id == id }
            ?: return@withLock report.copy(error = BackendException("not_found", "KPM is not registered"))
        if (module.pendingUnload) {
            return@withLock report.copy(error = BackendException("reboot_required", "KPM is pending reboot"))
        }
        var failure: BackendException? = null
        if (!module.loaded) {
            val loaded = commandUnlocked(listOf("kpm", "load", id, "--args", args))
            failure = loaded.error
            // The load reply follows the daemon's live-module verification.
            if (failure == null && loaded.detail?.let { it.id == id && it.loaded && !it.pendingUnload } != true) {
                failure = BackendException("unconfirmed_load", "KPM loading was not confirmed")
            }
        }
        if (failure == null) {
            failure = commandUnlocked(
                listOf("kpm", "autoload", id, if (temporary) "disable" else "enable")
            ).error
        }
        val refreshed = refreshUnlocked()
        if (failure == null && refreshed.error == null &&
            refreshed.modules?.any { it.id == id && it.loaded && !it.pendingUnload && it.autoload == !temporary } != true
        ) {
            failure = BackendException("unconfirmed_autoload", "KPM auto-load setting was not confirmed")
        }
        refreshed.copy(error = failure ?: refreshed.error)
    }

    private suspend fun mutateUnlocked(arguments: List<String>): KpmReport {
        if (arguments.getOrNull(1) != "integration") return mutateAndRefreshUnlocked(arguments)
        integrationRevision.incrementAndGet()
        verification = null
        var checkedStatus: KpmStatus? = null
        return try {
            mutateAndRefreshUnlocked(arguments).also { checkedStatus = it.status }
        } finally {
            val revision = integrationRevision.incrementAndGet()
            verification = checkedStatus?.takeIf { it.integrationVerified && it.stateKnown }?.let {
                IntegrationVerification(revision, System.nanoTime(), it)
            }
        }
    }

    private suspend fun mutateAndRefreshUnlocked(arguments: List<String>): KpmReport {
        val result = commandUnlocked(arguments)
        // A command may have changed the kernel before reporting a failure.
        val refreshed = if (arguments.getOrNull(1) == "import") {
            listUnlocked()
        } else {
            refreshUnlocked(runtimeOnly = arguments.getOrNull(1) != "integration")
        }
        var failure = result.error
        if (arguments.getOrNull(1) == "import" && failure == null && result.detail != null) {
            val imported = refreshed.modules?.find { it.id == result.detail.id } ?: result.detail
            if (imported.loaded || imported.pendingUnload) {
                failure = BackendException("already_loaded", "KPM is already resident")
            }
        }
        if (arguments.getOrNull(1) == "remove" && failure == null && refreshed.error == null &&
            refreshed.modules?.any { it.id == arguments.getOrNull(2) } == true
        ) {
            failure = BackendException("unconfirmed_remove", "KPM removal was not confirmed")
        }
        return refreshed.copy(detail = result.detail, error = failure ?: refreshed.error, output = result.output)
    }

    private suspend fun commandUnlocked(arguments: List<String>): KpmReport {
        var failure: BackendException? = null
        var output: String? = null
        var detail: KpmModule? = null
        try {
            require(arguments.firstOrNull() == "kpm")
            val result = executor.execute(arguments)
            val data = KpmProtocol.unwrap(result)
            if (arguments.getOrNull(1) in listOf("import", "load", "autoload")) {
                detail = KpmProtocol.decode<KpmModule>(result)
                if (detail.id.isBlank()) throw BackendException("invalid_data", "Backend returned an empty KPM ID")
                if (arguments[1] != "import" && detail.id != arguments.getOrNull(2)) {
                    throw BackendException("invalid_data", "Backend returned a different KPM ID")
                }
            }
            if (arguments.getOrNull(1) == "control") {
                output = ((data as? JsonObject)?.get("output") as? JsonPrimitive)?.contentOrNull
                    ?: data.toString()
            }
        } catch (e: Exception) {
            failure = e.backendError()
        }
        return KpmReport(detail = detail, error = failure, output = output)
    }

    private suspend fun refreshUnlocked(runtimeOnly: Boolean = true): KpmReport {
        var failure: BackendException? = null
        val status = try {
            val arguments = listOf("kpm", "status") + if (runtimeOnly) listOf("--runtime-only") else emptyList()
            KpmProtocol.decode<KpmStatus>(executor.execute(arguments)).let {
                if (runtimeOnly) it.copy(integrationVerified = false) else it
            }
        } catch (e: Exception) {
            failure = e.backendError()
            null
        }
        val listed = listUnlocked()
        latestStatus = status
        if (status == null || verification?.status?.let {
                !it.sameIntegrationState(status) || it.upgradeAvailable != status.upgradeAvailable
            } == true
        ) {
            verification = null
        }
        return KpmReport(status, listed.modules, error = failure ?: listed.error, statusQueried = true)
    }

    private suspend fun listUnlocked(): KpmReport = try {
        val modules = KpmProtocol.decode<List<KpmModule>>(executor.execute(listOf("kpm", "list")))
        if (modules.any { it.id.isBlank() } || modules.map { it.id }.toSet().size != modules.size) {
            throw BackendException("invalid_data", "Backend returned empty or duplicate KPM IDs")
        }
        KpmReport(modules = modules)
    } catch (e: Exception) {
        KpmReport(error = e.backendError())
    }

}

fun Exception.backendError(): BackendException {
    if (this is CancellationException) throw this
    return this as? BackendException ?: BackendException("manager", message ?: javaClass.simpleName)
}
