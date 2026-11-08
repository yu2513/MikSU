package io.milksu.manager.data.model

enum class MainTab {
    HOME, KPM, SUPERUSER, MODULE, SETTINGS;

    companion object {
        fun visible(kpmEnabled: Boolean): List<MainTab> =
            entries.filter { it != KPM || kpmEnabled }

        fun destination(name: String?, tabs: List<MainTab>): MainTab =
            restore(name).takeIf { it in tabs } ?: HOME

        fun restore(name: String?, legacyIndex: Int? = null): MainTab =
            entries.firstOrNull { it.name == name }
                ?: when (legacyIndex) {
                    1 -> SUPERUSER
                    2 -> MODULE
                    3 -> SETTINGS
                    else -> HOME
                }
    }
}
