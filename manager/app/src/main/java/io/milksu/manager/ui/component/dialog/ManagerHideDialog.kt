package io.milksu.manager.ui.component.dialog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.milksu.manager.R
import io.milksu.manager.data.system.ManagerAppearance
import io.milksu.manager.data.system.ManagerIcon
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.LocalUiMode
import io.milksu.manager.ui.UiMode
import io.milksu.manager.ui.component.material.ExpressiveDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton as MaterialTextButton
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

@Composable
fun ManagerHideDialog(
    show: Boolean,
    onConfirm: (String, Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    var name by rememberSaveable { mutableStateOf("") }
    var iconUri by rememberSaveable { mutableStateOf<String?>(null) }
    var iconRevision by rememberSaveable { mutableStateOf(0) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var decodedUri by remember { mutableStateOf<String?>(null) }
    var iconError by remember { mutableStateOf<String?>(null) }
    val defaultName = stringResource(R.string.settings_manager_hide_default_name)
    val invalidIcon = stringResource(R.string.settings_manager_hide_icon_invalid)
    val pickerUnavailable = stringResource(R.string.settings_manager_hide_icon_picker_unavailable)
    val validatedName = remember(name, defaultName) {
        try {
            ManagerAppearance.validateHiddenName(name.ifEmpty { defaultName })
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val nameError = if (validatedName == null) {
        stringResource(R.string.settings_manager_hide_name_invalid)
    } else null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            preview = null
            decodedUri = null
            iconError = null
            iconUri = uri.toString()
            iconRevision++
        }
    }

    LaunchedEffect(iconUri, iconRevision) {
        preview = null
        decodedUri = null
        iconError = null
        val selected = iconUri ?: return@LaunchedEffect
        try {
            val bitmap = withContext(Dispatchers.IO) {
                ManagerIcon.decode(ksuApp, Uri.parse(selected))
            }
            preview = bitmap.asImageBitmap()
            decodedUri = selected
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            iconError = invalidIcon
        }
    }

    val canConfirm = validatedName != null && iconError == null &&
        (iconUri == null || (decodedUri == iconUri && preview != null))
    val confirm: () -> Unit = {
        if (canConfirm) {
            onConfirm(requireNotNull(validatedName), iconUri?.let(Uri::parse))
        }
    }
    val pickIcon: () -> Unit = {
        try {
            picker.launch("image/*")
        } catch (_: Exception) {
            iconError = pickerUnavailable
        }
    }
    val resetIcon: () -> Unit = {
        iconUri = null
        preview = null
        decodedUri = null
        iconError = null
    }
    val miuix = LocalUiMode.current == UiMode.Miuix
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ManagerHideIconPreview(preview, miuix, pickIcon)
            val previewName = validatedName ?: defaultName
            if (miuix) {
                MiuixText(
                    text = previewName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                MaterialText(
                    text = previewName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (iconUri != null || iconError != null) {
                val resetLabel = stringResource(R.string.settings_manager_hide_icon_reset)
                if (miuix) {
                    MiuixIconButton(onClick = resetIcon) {
                        MiuixIcon(Icons.Rounded.RestartAlt, contentDescription = resetLabel)
                    }
                } else {
                    MaterialIconButton(onClick = resetIcon) {
                        MaterialIcon(Icons.Rounded.RestartAlt, contentDescription = resetLabel)
                    }
                }
            }
            if (miuix) {
                MiuixTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_manager_hide_name),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameError != null) {
                    MiuixText(text = nameError, color = MiuixTheme.colorScheme.error)
                }
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { MaterialText(stringResource(R.string.settings_manager_hide_name)) },
                    placeholder = { MaterialText(defaultName) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { message -> { MaterialText(message) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            iconError?.let { message ->
                if (miuix) {
                    MiuixText(text = message, color = MiuixTheme.colorScheme.error)
                } else {
                    MaterialText(text = message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (miuix) {
        WindowDialog(
            show = show,
            title = stringResource(R.string.settings_manager_hide),
            onDismissRequest = onDismiss,
            content = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        content()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiuixTextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        MiuixTextButton(
                            text = stringResource(R.string.confirm),
                            enabled = canConfirm,
                            onClick = confirm,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    } else {
        ExpressiveDialog(
            title = { MaterialText(stringResource(R.string.settings_manager_hide)) },
            onDismissRequest = onDismiss,
            text = content,
            dismissButton = {
                MaterialTextButton(onClick = onDismiss) {
                    MaterialText(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                MaterialTextButton(enabled = canConfirm, onClick = confirm) {
                    MaterialText(stringResource(R.string.confirm))
                }
            },
        )
    }
}

@Composable
private fun ManagerHideIconPreview(
    preview: ImageBitmap?,
    miuix: Boolean,
    onPickIcon: () -> Unit,
) {
    val defaultPreview = remember { ManagerIcon.default(ksuApp).asImageBitmap() }
    val pickLabel = stringResource(R.string.settings_manager_hide_icon_pick)
    val background = if (miuix) MiuixTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val foreground = if (miuix) MiuixTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .clickable(role = Role.Button, onClick = onPickIcon)
                .semantics { contentDescription = pickLabel },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = preview ?: defaultPreview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(96.dp),
            )
        }
        val buttonModifier = Modifier.align(Alignment.BottomEnd).size(48.dp)
            .background(background, CircleShape)
        if (miuix) {
            MiuixIconButton(onClick = onPickIcon, modifier = buttonModifier) {
                MiuixIcon(Icons.Rounded.PhotoLibrary, contentDescription = pickLabel, tint = foreground)
            }
        } else {
            MaterialIconButton(onClick = onPickIcon, modifier = buttonModifier) {
                MaterialIcon(Icons.Rounded.PhotoLibrary, contentDescription = pickLabel, tint = foreground)
            }
        }
    }
}
