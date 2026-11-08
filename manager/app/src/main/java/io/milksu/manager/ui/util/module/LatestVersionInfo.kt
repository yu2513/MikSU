package io.milksu.manager.ui.util.module

data class LatestVersionInfo(
    val versionCode: Int = 0,
    val downloadUrl: String = "",
    val changelog: String = "",
    val version: String = "",
    val assetName: String = "",
    val assetSize: Long = 0,
    val assetSha256: String? = null,
)
