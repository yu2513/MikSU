package io.milksu.manager.ui.screen.home

data class SusfsHomeCache(
    val available: Boolean = false,
    val version: String? = null,
    val variant: String? = null,
    val builtin: Boolean? = null,
) {
    fun restore(integrationEnabled: Boolean): SusfsHomeInfo {
        if (!integrationEnabled) return SusfsHomeInfo()
        if (!available) return SusfsHomeInfo(integrationEnabled = true)
        return SusfsHomeInfo(
            integrationEnabled = true,
            availability = SusfsAvailability.Available,
            version = SusfsHomeInfo.runtimeValue(version),
            variant = SusfsHomeInfo.runtimeValue(variant),
            builtin = builtin,
        )
    }

    companion object {
        const val AVAILABLE_KEY = "home_susfs_runtime_available"
        const val VERSION_KEY = "home_susfs_runtime_version"
        const val VARIANT_KEY = "home_susfs_runtime_variant"
        const val BUILTIN_KEY = "home_susfs_runtime_builtin"

        fun from(info: SusfsHomeInfo): SusfsHomeCache =
            if (info.integrationEnabled && info.supported) {
                SusfsHomeCache(
                    available = true,
                    version = SusfsHomeInfo.runtimeValue(info.version),
                    variant = SusfsHomeInfo.runtimeValue(info.variant),
                    builtin = info.builtin,
                )
            } else SusfsHomeCache()
    }
}
