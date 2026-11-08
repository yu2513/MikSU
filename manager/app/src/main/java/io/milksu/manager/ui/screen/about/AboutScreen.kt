package io.milksu.manager.ui.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import io.milksu.manager.BuildConfig
import io.milksu.manager.R
import io.milksu.manager.ui.LocalUiMode
import io.milksu.manager.ui.UiMode
import io.milksu.manager.ui.navigation3.LocalNavigator

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val htmlString = stringResource(
        id = R.string.milksu_about_source,
        "<b><a href=\"https://github.com/Xiaogugtfgdfhgrh/MikSU\">GitHub</a></b>",
        "<b><a href=\"https://t.me/KernelSU\">Telegram</a></b>"
    )
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        links = extractLinks(htmlString),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}
