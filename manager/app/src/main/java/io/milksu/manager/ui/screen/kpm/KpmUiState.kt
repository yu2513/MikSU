package io.milksu.manager.ui.screen.kpm

import io.milksu.manager.data.system.BackendException
import io.milksu.manager.data.system.KpmModule
import io.milksu.manager.data.system.KpmReport
import io.milksu.manager.data.system.KpmStatus
import io.milksu.manager.data.system.KPM_VERIFICATION_TTL_NANOS
import io.milksu.manager.data.system.SusfsStatus
import io.milksu.manager.data.system.integrationState

data class SusfsState(
    val supported: Boolean? = null,
    val loaded: Boolean? = null,
    val builtin: Boolean? = null,
    val autoload: Boolean? = null,
    val version: String = "",
    val kmi: String = "",
    val reason: String? = null,
    val error: BackendException? = null,
    val queryError: BackendException? = null,
    val variant: String? = null,
) {
    fun merge(status: SusfsStatus) = copy(
        supported = status.supported,
        loaded = status.loaded,
        builtin = status.builtin,
        autoload = status.autoload,
        version = status.version.orEmpty(),
        variant = status.variant,
        kmi = status.kmi.orEmpty(),
        reason = status.reason,
        error = null,
        queryError = null,
    )

    fun queryFailed(failure: BackendException) = copy(error = failure, queryError = failure)
}

data class KpmUiState(
    val status: KpmStatus? = null,
    val modules: List<KpmModule> = emptyList(),
    val detail: KpmModule? = null,
    val hasRead: Boolean = false,
    val busy: Boolean = false,
    val unavailable: String? = null,
    val error: BackendException? = null,
    val susfs: SusfsState = SusfsState(),
    val canPatchBoot: Boolean = false,
    val output: String? = null,
    val safeMode: Boolean = false,
    val hasFreshStatus: Boolean = false,
    val hasFreshIntegrationStatus: Boolean = false,
    val kpmTabEnabled: Boolean = false,
    val navigationReady: Boolean = false,
    val pendingImport: KpmModule? = null,
    val pendingImportIsNew: Boolean = false,
    val integrationVerification: KpmStatus? = null,
    val integrationVerificationError: BackendException? = null,
    val integrationVerificationCompletedAtNanos: Long? = null,
) {
    val integration get() = integrationState(status, unavailable, error)
    val canMutate get() = !busy && unavailable == null && hasRead && hasFreshStatus && status != null && error == null
    val canImport get() = canMutate
    val showImport get() = unavailable == null && hasRead && status != null
    val canLoad get() = canImport && !safeMode && status?.let {
        it.stateKnown && it.active && it.configuredEnabled && it.supported && !it.rebootRequired && !it.transitionFailed
    } == true
    private val hasIntegrationAccess get() = !busy && unavailable == null &&
        (if (status?.integrationVerified == false) hasFreshStatus else hasFreshIntegrationStatus) &&
        canPatchBoot && status?.stateKnown == true
    val canChangeIntegration get() = status?.let { canSetIntegration(!it.configuredEnabled) } == true
    val requiresReboot get() = status?.let {
        !it.transitionFailed && it.rebootRequired
    } == true
    val canReboot get() = canMutate && requiresReboot
    private val hasKnownSusfsState get() = !busy && unavailable == null && susfs.queryError == null &&
        susfs.supported != null && susfs.loaded != null && susfs.builtin != null && susfs.autoload != null
    val canMutateSusfs get() = hasKnownSusfsState && !(susfs.loaded == true && susfs.supported == false)
    val canChangeSusfsAutoload get() = hasKnownSusfsState &&
        (susfs.autoload == true || (canMutateSusfs && !safeMode && susfs.builtin != true))
    val susfsIntegrationEnabled get() = susfs.autoload == true ||
        (susfs.loaded == true && susfs.builtin != true)
    val canChangeSusfsIntegration get() = canChangeSusfsAutoload ||
        (hasKnownSusfsState && susfs.loaded == true && susfs.builtin != true)
    private val matchingIntegrationVerification get() = integrationVerification?.takeIf {
        status?.integrationVerified == false && hasFreshStatus && error == null &&
            it.integrationVerified && it.stateKnown &&
            status.sameIntegrationState(it)
    }
    private val verificationIsCurrent get() = integrationVerificationCompletedAtNanos?.let {
        System.nanoTime() - it in 0L until KPM_VERIFICATION_TTL_NANOS
    } == true
    private val settingsIntegrationStatus get() = when {
        status?.integrationVerified == true -> status
        integrationVerificationError != null -> null
        matchingIntegrationVerification != null ->
            matchingIntegrationVerification.takeIf { verificationIsCurrent }
        else -> status
    }
    val integrationSettingsError get() = integrationVerificationError?.message ?:
        matchingIntegrationVerification?.takeIf { verificationIsCurrent }?.let {
            val enabling = !it.configuredEnabled || it.upgradeAvailable
            if ((if (enabling) it.canEnable else it.canDisable) != true) it.reason else null
        }
    val showUpgradeIntegration get() =
        (settingsIntegrationStatus ?: status)?.let { it.configuredEnabled && it.upgradeAvailable } == true
    val canUpgradeIntegration get() = showUpgradeIntegration && canSetIntegration(true)

    fun canSetIntegration(enabled: Boolean) = hasIntegrationAccess && settingsIntegrationStatus?.let {
        ((if (enabled) it.canEnable else it.canDisable) ?:
            if (status?.integrationVerified == true && it.integrationVerified) {
                it.canConfigure ?: it.supported
            } else false) &&
            (it.configuredEnabled != enabled || (enabled && it.upgradeAvailable))
    } == true &&
        (!safeMode || !enabled)

    fun canChangeAutoload(module: KpmModule?) = canMutate && module?.let {
        it.sha256.isNotBlank() && (!safeMode || it.autoload) && (!it.pendingUnload || it.autoload)
    } == true

    fun canUnload(module: KpmModule?) = canMutate && status?.canUnload == true &&
        module?.let { it.loaded && !it.pendingUnload } == true

    fun canRemove(module: KpmModule?) = canMutate && module?.let {
        !it.pendingUnload && (!it.loaded || status?.canUnload == true)
    } == true

    fun withAccess(unavailable: String?, canPatchBoot: Boolean, safeMode: Boolean) = copy(
        unavailable = unavailable,
        canPatchBoot = canPatchBoot,
        safeMode = safeMode,
        hasFreshStatus = hasFreshStatus && unavailable == null,
        hasFreshIntegrationStatus = hasFreshIntegrationStatus && unavailable == null,
        integrationVerification = integrationVerification.takeIf { unavailable == null },
        susfs = if (unavailable == null) susfs else susfs.queryFailed(
            BackendException("root_unavailable", unavailable)
        ),
    )

    fun mergeIntegrationVerification(report: KpmReport, nowNanos: Long = System.nanoTime()): KpmUiState {
        val checked = report.status
        if (checked != null && (!checked.integrationVerified || !checked.stateKnown ||
                (status != null && !status.sameIntegrationState(checked)))
        ) return this
        return copy(
            integrationVerification = checked,
            integrationVerificationError = report.error,
            integrationVerificationCompletedAtNanos = report.verificationCompletedAtNanos ?: nowNanos,
        )
    }

    fun merge(report: KpmReport, detailId: String? = null): KpmUiState {
        val freshSnapshot = report.status != null && report.modules != null && report.error == null
        val incoming = report.status
        val keepIntegration = incoming?.integrationVerified == false && hasFreshIntegrationStatus &&
            status?.let { incoming.sameIntegrationState(it) } == true
        val keepVerification = incoming != null && status?.let {
            incoming.sameIntegrationState(it) && incoming.upgradeAvailable == it.upgradeAvailable
        } == true
        return copy(
            status = incoming ?: status,
            integrationVerification = when {
                incoming?.integrationVerified == true && incoming.stateKnown -> incoming
                incoming != null -> integrationVerification?.takeIf {
                    keepVerification && incoming.sameIntegrationState(it)
                }
                report.statusQueried -> null
                else -> integrationVerification
            },
            integrationVerificationCompletedAtNanos = when {
                incoming?.integrationVerified == true && incoming.stateKnown ->
                    report.verificationCompletedAtNanos ?: System.nanoTime()
                incoming != null && !keepVerification -> null
                report.statusQueried && incoming == null -> null
                else -> integrationVerificationCompletedAtNanos
            },
            integrationVerificationError = when {
                incoming?.integrationVerified == true -> null
                incoming != null && !keepVerification -> null
                else -> integrationVerificationError
            },
            kpmTabEnabled = report.status?.takeIf { it.stateKnown }?.configuredEnabled ?: kpmTabEnabled,
            navigationReady = report.status?.stateKnown == true || navigationReady,
            modules = report.modules ?: modules,
            detail = report.detail ?: if (report.modules != null) report.modules.find { it.id == detailId } else detail,
            hasRead = true,
            // Detail-only success must not clear a failed snapshot or make cached status fresh.
            hasFreshStatus = when {
                report.error != null -> false
                freshSnapshot -> true
                else -> hasFreshStatus
            },
            hasFreshIntegrationStatus = when {
                incoming != null -> incoming.integrationVerified || keepIntegration
                report.statusQueried -> false
                else -> hasFreshIntegrationStatus
            },
            error = report.error ?: if (freshSnapshot) null else error,
            output = report.output ?: output,
            pendingImport = pendingImport?.let { pending ->
                report.modules?.find { it.id == pending.id } ?: pending
            },
        )
    }
}
