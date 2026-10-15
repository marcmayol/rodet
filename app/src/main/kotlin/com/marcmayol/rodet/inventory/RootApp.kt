package com.marcmayol.rodet.inventory

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.EstadoActualizacion
import com.marcm.actualizador.Modo
import com.marcmayol.rodet.BuildConfig
import com.marcmayol.rodet.R
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Las cuatro pestañas. La de grabar tags es el flujo de siempre, con su propia
 * barra; las otras tres comparten esta.
 */
@Composable
fun RootApp(
    vm: InventoryViewModel,
    onExit: () -> Unit,
    onFromPhoto: () -> Unit,
    onWriteTag: (Spool) -> Unit,
    updates: Actualizador?,
    flow: @Composable () -> Unit,
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val text = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.dismissNotice()
        }
    }

    BackHandler(enabled = tab != Tab.TAGS) {
        when {
            tab == Tab.SPOOLS && vm.back() -> Unit
            tab != Tab.SPOOLS -> vm.selectTab(Tab.SPOOLS)
            else -> onExit()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            // Tocar la pestaña en la que ya se esta vuelve a su raiz.
                            if (t == Tab.SPOOLS && tab == Tab.SPOOLS) vm.openSpools(SpoolRoute.List)
                            vm.selectTab(t)
                        },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.SPOOLS -> Icons.Default.Inventory2
                                    Tab.PRINTS -> Icons.Default.Calculate
                                    Tab.STATS -> Icons.Default.BarChart
                                    Tab.TAGS -> Icons.Default.Nfc
                                },
                                null,
                            )
                        },
                        // Con la letra al 150 % "Estadisticas" no cabe: se encoge antes que cortarse.
                        label = {
                            BasicText(
                                tabLabel(t),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current),
                                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (tab == Tab.TAGS) {
                flow()
            } else {
                InventoryApp(tab, vm, updates, onFromPhoto = { vm.selectTab(Tab.TAGS); onFromPhoto() }, onWriteTag = { vm.selectTab(Tab.TAGS); onWriteTag(it) })
            }
        }
    }
}

@Composable
private fun tabLabel(t: Tab) = stringResource(
    when (t) {
        Tab.SPOOLS -> R.string.tab_spools
        Tab.PRINTS -> R.string.tab_prints
        Tab.STATS -> R.string.tab_stats
        Tab.TAGS -> R.string.tab_tags
    },
)

@Composable
private fun noticeText(n: Notice): String = when (n) {
    Notice.GcodeUnreadable -> stringResource(R.string.notice_gcode_unreadable)
    is Notice.GcodeRead -> stringResource(R.string.notice_gcode_read, n.filaments, n.matched)
    Notice.Exported -> stringResource(R.string.notice_exported)
    Notice.Imported -> stringResource(R.string.notice_imported)
    Notice.ImportFailed -> stringResource(R.string.notice_import_failed)
    Notice.UnknownTag -> stringResource(R.string.notice_unknown_tag)
    Notice.NeedTare -> stringResource(R.string.notice_need_tare)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryApp(tab: Tab, vm: InventoryViewModel, updates: Actualizador?, onFromPhoto: () -> Unit, onWriteTag: (Spool) -> Unit) {
    val inv by vm.inventory.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val calc by vm.calc.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var manualCheck by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val update = updates?.estado?.collectAsStateWithLifecycle()
    // Al abrir, en silencio: si no hay red o no hay nada, no se ve nada.
    LaunchedEffect(updates) { updates?.comprobar(Modo.AUTOMATICO) }

    val openGcode = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::openGcode) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportTo) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFrom) }

    val inSub = tab == Tab.SPOOLS && route != SpoolRoute.List
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            tab == Tab.SPOOLS && route == SpoolRoute.Edit ->
                                stringResource(if (draft.spoolId == null) R.string.title_new_spool else R.string.title_edit_spool)
                            tab == Tab.SPOOLS && route is SpoolRoute.Detail -> stringResource(R.string.title_spool)
                            tab == Tab.SPOOLS -> stringResource(R.string.title_spools)
                            tab == Tab.PRINTS -> stringResource(R.string.title_prints)
                            else -> stringResource(R.string.title_stats)
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (inSub) IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    if (tab == Tab.SPOOLS && route == SpoolRoute.List) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; export.launch("rodet-${LocalDate.now()}.json") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_import)) }, onClick = { menu = false; import.launch(arrayOf("application/json", "text/plain", "*/*")) })
                            if (updates != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.update_check, BuildConfig.VERSION_NAME)) },
                                    onClick = { menu = false; manualCheck = true; scope.launch { updates.comprobar(Modo.MANUAL) } },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val estado = update?.value
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (estado != null) {
                UpdateBanner(estado, onUpdate = { updates.actualizarAhora() })
                val quiet = estado is EstadoActualizacion.AlDia || estado is EstadoActualizacion.Error || estado is EstadoActualizacion.Comprobando
                if (manualCheck && quiet) {
                    Text(updateText(estado), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            Box(Modifier.weight(1f)) { Screens(tab, route, inv, settings, draft, calc, busy, vm, onFromPhoto, onWriteTag, onOpenGcode = { openGcode.launch(arrayOf("*/*")) }) }
        }
    }
}

@Composable
private fun Screens(
    tab: Tab,
    route: SpoolRoute,
    inv: Inventory,
    settings: CostSettings,
    draft: SpoolDraft,
    calc: CalcDraft,
    busy: Boolean,
    vm: InventoryViewModel,
    onFromPhoto: () -> Unit,
    onWriteTag: (Spool) -> Unit,
    onOpenGcode: () -> Unit,
) {
        val m = Modifier.fillMaxSize()
        when (tab) {
            Tab.SPOOLS -> when (val r = route) {
                SpoolRoute.List -> SpoolListScreen(inv, settings, vm, onFromPhoto, m)
                is SpoolRoute.Detail ->
                    if (inv.spool(r.id) == null) SpoolListScreen(inv, settings, vm, onFromPhoto, m)
                    else SpoolDetailScreen(r.id, inv, settings, vm, onWriteTag, m)
                SpoolRoute.Edit -> SpoolEditScreen(draft, settings, vm, m)
            }
            Tab.PRINTS -> PrintsScreen(inv, settings, calc, busy, vm, onOpenGcode = onOpenGcode, modifier = m)
            Tab.STATS -> StatsScreen(inv, settings, m)
            Tab.TAGS -> Unit
        }
}
