package io.milksu.manager.ui.screen.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import io.milksu.manager.BuildConfig

@Immutable
data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int
)

fun getManagerVersion(context: Context): ManagerVersion {
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    return ManagerVersion(
        versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME,
        versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: BuildConfig.VERSION_CODE.toLong(),
    )
}
