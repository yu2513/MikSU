package io.milksu.manager.ui.screen.home

import io.milksu.manager.data.system.CoreUpdatePolicy
import io.milksu.manager.ui.util.module.LatestVersionInfo

object HomeManagerUpdates {
    private fun canSave(release: LatestVersionInfo) =
        release.versionCode > 0 && release.assetSize > 0 &&
            release.downloadUrl.length in 1..4096 &&
            release.version.length in 1..128 && release.assetName.length in 1..255 &&
            (release.assetSha256 == null || Regex("[0-9a-fA-F]{64}").matches(release.assetSha256))

    fun offer(
        previous: LatestVersionInfo,
        installedVersionCode: Long,
        enabled: Boolean,
        endpoint: String,
        fetched: LatestVersionInfo? = null,
    ): LatestVersionInfo {
        if (!enabled) return LatestVersionInfo()
        return listOfNotNull(fetched, previous)
            .filter { CoreUpdatePolicy.isDownloadable(it, installedVersionCode, endpoint) }
            .maxByOrNull { it.versionCode } ?: LatestVersionInfo()
    }

    fun savePending(release: LatestVersionInfo?): List<Any> = release?.takeIf(::canSave)?.let {
        listOf(
            1, it.versionCode, it.downloadUrl, "", it.version,
            it.assetName, it.assetSize, it.assetSha256.orEmpty(),
        )
    } ?: emptyList()

    fun restorePending(saved: List<Any>): LatestVersionInfo? {
        if (saved.size != 8 || saved[0] != 1) return null
        val release = LatestVersionInfo(
            versionCode = saved[1] as? Int ?: return null,
            downloadUrl = saved[2] as? String ?: return null,
            changelog = "",
            version = saved[4] as? String ?: return null,
            assetName = saved[5] as? String ?: return null,
            assetSize = saved[6] as? Long ?: return null,
            assetSha256 = (saved[7] as? String ?: return null).ifEmpty { null },
        )
        if (saved[3] !is String) return null
        return release.takeIf(::canSave)
    }
}

suspend fun runHomeManagerUpdate(
    release: LatestVersionInfo,
    savePending: (LatestVersionInfo?) -> Unit,
    install: suspend (LatestVersionInfo) -> Boolean,
) {
    savePending(release)
    try {
        if (!install(release)) savePending(null)
    } catch (error: Exception) {
        savePending(null)
        throw error
    }
}
