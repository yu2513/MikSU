package io.milksu.manager.ui.screen.kpm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.milksu.manager.R
import io.milksu.manager.data.model.MainTab
import io.milksu.manager.data.system.KpmModule
import io.milksu.manager.data.system.KpmReport
import io.milksu.manager.data.system.KpmStatus
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.LocalMainPagerState
import io.milksu.manager.ui.LocalUiMode
import io.milksu.manager.ui.UiMode
import io.milksu.manager.ui.viewmodel.KpmViewModel
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar

@Composable
private fun systemViewModel(): KpmViewModel = viewModel(viewModelStoreOwner = ksuApp)

@Composable
private fun RefreshOnResume(isCurrentPage: Boolean, refresh: () -> Unit) {
    val latestCurrent by rememberUpdatedState(isCurrentPage)
    val latestRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(isCurrentPage) { if (isCurrentPage) latestRefresh() }
    var resumed by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (resumed && latestCurrent) latestRefresh()
        resumed = true
        onPauseOrDispose { }
    }
}

@Composable
fun KpmPager(bottomInnerPadding: Dp, isCurrentPage: Boolean) {
    if (LocalInspectionMode.current) {
        KpmContent(KpmUiState(), bottomInnerPadding, {}, {}, {})
        return
    }
    val vm = systemViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val mainPagerState = LocalMainPagerState.current
    var pickingDocument by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingDocument = false
        val kpmPage = mainPagerState.tabs.indexOf(MainTab.KPM)
        if (kpmPage >= 0) mainPagerState.animateToPage(kpmPage)
        uri?.let { vm.importDocument(it) }
    }
    RefreshOnResume(isCurrentPage) {
        if (!pickingDocument && !state.busy && state.pendingImport == null) vm.refresh()
    }
    KpmContent(
        state, bottomInnerPadding,
        onRefresh = { vm.refresh() },
        onImport = {
            pickingDocument = true
            picker.launch(arrayOf("*/*"))
        },
        onLoad = vm::selectModuleForLoad,
        onAction = vm::moduleAction,
        dialogs = {
            if (isCurrentPage) {
                KpmLoadDialog(state, vm::cancelImport, vm::loadImported)
                KpmResultDialog(
                    state.output, stringResource(R.string.kpm_control_title), vm::dismissOutput,
                )
                if (state.pendingImport == null) {
                    KpmResultDialog(state.error?.display, stringResource(R.string.kpm_error), vm::dismissError)
                }
            }
        },
    )
}

@Composable
fun FeatureScaffold(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    busy: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val scheme = if (miuix) {
        val colors = MiuixTheme.colorScheme
        val base = if (colors.surface.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
            background = colors.background, onBackground = colors.onBackground,
            surface = colors.surface, onSurface = colors.onSurface,
            surfaceVariant = colors.surface, surfaceDim = colors.background, surfaceBright = colors.surface,
            surfaceContainer = colors.surface, surfaceContainerLow = colors.surface,
            surfaceContainerLowest = colors.background, surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.surface,
            onSurfaceVariant = colors.onSurfaceVariantSummary, outline = colors.outline,
        )
    } else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme) {
        val navigation: @Composable () -> Unit = {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.kpm_back))
                }
            }
        }
        val actions: @Composable () -> Unit = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                onRefresh?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Refresh, stringResource(R.string.kpm_refresh)) }
                }
            }
        }
        if (miuix) {
            MiuixScaffold(
                topBar = { title?.let { MiuixTopAppBar(title = it, navigationIcon = navigation, actions = { actions() }) } },
                floatingActionButton = fab,
                content = content,
            )
        } else {
            Scaffold(
                topBar = { title?.let { LargeFlexibleTopAppBar(title = { Text(it) }, navigationIcon = navigation, actions = { actions() }) } },
                floatingActionButton = fab,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                content = content,
            )
        }
    }
}

@Composable
fun KpmContent(
    state: KpmUiState,
    bottomPadding: Dp,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onLoad: (String) -> Unit,
    onAction: (String, String, String) -> Unit = { _, _, _ -> },
    dialogs: @Composable () -> Unit = {},
) {
    var controlId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleModules = state.modules.filter { it.id != state.pendingImport?.id || it.loaded }
    FeatureScaffold(
        title = stringResource(R.string.kpm_page_title),
        busy = state.busy, onRefresh = onRefresh,
        fab = {
            if (state.showImport) {
                FloatingActionButton(
                    onClick = { if (state.canImport) onImport() },
                    modifier = Modifier.padding(bottom = bottomPadding + 12.dp),
                ) {
                    Icon(Icons.Filled.Add, stringResource(R.string.kpm_import))
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.busy,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).padding(bottom = bottomPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (visibleModules.isEmpty()) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    when {
                        state.error != null -> Text(
                            state.error.display, Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.unavailable != null -> Text(state.unavailable, Modifier.padding(24.dp))
                        state.hasRead -> Text(
                            stringResource(R.string.kpm_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        state.busy -> CircularProgressIndicator()
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleModules, key = { it.id }) { module ->
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(module.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${module.version}, ${stringResource(R.string.kpm_author, module.author)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(R.string.kpm_parameter_value, module.args.ifBlank { "(null)" }),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (module.description.isNotBlank()) {
                                    Text(module.description, Modifier.padding(vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (module.pendingUnload) {
                                    Text(stringResource(R.string.kpm_pending_unload), color = MaterialTheme.colorScheme.primary)
                                }
                                module.lastError?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                                HorizontalDivider()
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                                ) {
                                    if (module.loaded) {
                                        FilledTonalIconButton(
                                            modifier = Modifier.width(64.dp),
                                            onClick = { controlId = module.id },
                                            enabled = state.canLoad && module.hasControl && !module.pendingUnload,
                                        ) {
                                            Icon(Icons.Filled.Settings, stringResource(R.string.kpm_control))
                                        }
                                    } else {
                                        FilledTonalIconButton(
                                            modifier = Modifier.width(64.dp),
                                            onClick = { onLoad(module.id) },
                                            enabled = state.canLoad && !module.pendingUnload,
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.kpm_load))
                                        }
                                    }
                                    FilledTonalIconButton(
                                        modifier = Modifier.width(64.dp),
                                        onClick = { removeId = module.id },
                                        enabled = state.canRemove(module),
                                    ) {
                                        Icon(Icons.Filled.Delete, stringResource(
                                            if (module.loaded) R.string.kpm_unload else R.string.kpm_remove,
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        state.modules.find { it.id == controlId }?.let { module ->
            KpmControlDialog(module, state.busy, { controlId = null }) { args ->
                controlId = null
                onAction(module.id, "control", args)
            }
        }
        state.modules.find { it.id == removeId }?.let { module ->
            AlertDialog(
                onDismissRequest = { removeId = null },
                title = { Text(stringResource(R.string.kpm_dialog_title)) },
                text = { Text(stringResource(R.string.kpm_unload_message, module.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        removeId = null
                        onAction(module.id, "remove", "")
                    }, enabled = !state.busy) {
                        Text(stringResource(if (module.loaded) R.string.kpm_unload else R.string.kpm_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removeId = null }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
        dialogs()
    }
}

@Composable
private fun KpmLoadDialog(
    state: KpmUiState,
    onCancel: () -> Unit,
    onLoad: (Boolean) -> Unit,
) {
    val module = state.pendingImport ?: return
    var temporary by rememberSaveable(module.id) { mutableStateOf(false) }
    val canConfirm = !state.busy && state.unavailable == null && !state.safeMode &&
        state.status?.let {
            it.stateKnown && it.supported && it.active && it.configuredEnabled &&
                !it.rebootRequired && !it.transitionFailed
        } == true && !module.pendingUnload
    AlertDialog(
        onDismissRequest = { if (!state.busy) onCancel() },
        title = { Text(stringResource(R.string.kpm_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.kpm_load_message, module.name))
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.busy) { temporary = !temporary },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = temporary, onCheckedChange = { temporary = it }, enabled = !state.busy)
                    Text(stringResource(R.string.kpm_temporary_load))
                }
                state.error?.let { Text(it.display, color = MaterialTheme.colorScheme.error) }
                if (state.requiresReboot) {
                    Text(stringResource(R.string.kpm_pending_reboot), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLoad(temporary) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.kpm_load))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.busy) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun KpmControlDialog(
    module: KpmModule,
    busy: Boolean,
    onDismiss: () -> Unit,
    onControl: (String) -> Unit,
) {
    var arguments by rememberSaveable(module.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kpm_control_title)) },
        text = {
            OutlinedTextField(
                value = arguments,
                onValueChange = { if (it.length <= 16384 && '\u0000' !in it) arguments = it },
                label = { Text(stringResource(R.string.kpm_arguments)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        },
        confirmButton = {
            TextButton(onClick = { onControl(arguments) }, enabled = !busy && arguments.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun KpmResultDialog(output: String?, title: String, onDismiss: () -> Unit) {
    output?.takeIf { it.isNotBlank() }?.let {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
fun RootUnavailableContent() {
    FeatureScaffold(stringResource(R.string.kpm_unavailable)) { padding ->
        Text(stringResource(R.string.kpm_root_required), Modifier.padding(padding).padding(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun KpmOffPreview() {
    CompositionLocalProvider(LocalUiMode provides UiMode.Material) {
        KpmContent(KpmUiState().merge(KpmReport(KpmStatus(true, false, false, false), emptyList())), 0.dp, {}, {}, {})
    }
}

@Preview(name = "KPM Material Dark", showBackground = true)
@Composable
private fun KpmDarkPreview() {
    CompositionLocalProvider(LocalUiMode provides UiMode.Material) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            KpmContent(
                KpmUiState().merge(KpmReport(KpmStatus(true, true, false, true), emptyList())),
                0.dp, {}, {}, {},
            )
        }
    }
}

@Preview(name = "KPM Miuix", showBackground = true)
@Composable
private fun KpmMiuixPreview() {
    CompositionLocalProvider(LocalUiMode provides UiMode.Miuix) {
        MiuixTheme {
            KpmContent(
                KpmUiState().merge(KpmReport(KpmStatus(true, false, false, false), emptyList())),
                0.dp, {}, {}, {},
            )
        }
    }
}
