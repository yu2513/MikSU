package io.milksu.manager.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import io.milksu.manager.ui.LocalUiMode
import io.milksu.manager.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
