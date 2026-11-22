package com.example.holzaffe

import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

private enum class MainScreen { HOME, APPS, TASKS, PICK_REDIRECT_APP, PICK_WIDGET }

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var widgetHost: AppWidgetHost

    private var screen by mutableStateOf(MainScreen.HOME)
    private var widgetMinutes by mutableIntStateOf(1)
    private var widgetCustomLabel by mutableStateOf("")
    private var widgetError by mutableStateOf<String?>(null)

    // Pending widget metadata while Android's bind permission / widget configurator runs.
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetMinutes: Int = 1
    private var pendingWidgetCustomLabel: String = ""
    private var pendingWidgetReconfigure: Boolean = false
    // Bumped whenever a widget task is added/reconfigured so Compose reloads Store.
    private var tasksRevision by mutableIntStateOf(0)

    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        if (result.resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            proceedAfterWidgetBound(id)
        } else {
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                runCatching { widgetHost.deleteAppWidgetId(id) }
            }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetReconfigure = false
            widgetError = "Widget-Berechtigung wurde nicht erteilt."
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleWidgetConfigResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        widgetHost = AppWidgetHost(applicationContext, GateActivity.WIDGET_HOST_ID)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    var redirectMinutes by remember { mutableIntStateOf(1) }
                    val control = remember { RunControl() }
                    BackHandler(enabled = screen != MainScreen.HOME || control.running) {
                        screen = when (screen) {
                            MainScreen.PICK_REDIRECT_APP, MainScreen.PICK_WIDGET -> MainScreen.TASKS
                            else -> MainScreen.HOME
                        }
                        widgetError = null
                        control.running = false
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize()) {
                        when (screen) {
                            MainScreen.APPS -> Apps(store, onExit = { screen = MainScreen.HOME })
                            MainScreen.PICK_REDIRECT_APP -> RedirectAppPicker(
                                minutes = redirectMinutes,
                                onPick = { entry ->
                                    val created = GateTask(
                                        id = "a_${System.currentTimeMillis()}_${store.gateTasks.size}",
                                        text = entry.label,
                                        minutes = redirectMinutes,
                                        kind = GateTaskKind.APP,
                                        targetPackage = entry.pkg,
                                    )
                                    store.gateTasks = store.gateTasks + created
                                    store.gateActiveTaskIds = store.gateActiveTaskIds + created.id
                                    tasksRevision++
                                    screen = MainScreen.TASKS
                                },
                                onCancel = { screen = MainScreen.TASKS },
                            )
                            MainScreen.PICK_WIDGET -> WidgetPicker(
                                minutes = widgetMinutes,
                                errorMessage = widgetError,
                                onPick = { entry ->
                                    widgetError = null
                                    onSelectWidgetProvider(entry.info, widgetMinutes, widgetCustomLabel)
                                },
                                onCancel = {
                                    widgetError = null
                                    screen = MainScreen.TASKS
                                },
                            )
                            MainScreen.TASKS -> GateTasksScreen(
                                store = store,
                                revision = tasksRevision,
                                onPickApp = { mins ->
                                    redirectMinutes = mins
                                    screen = MainScreen.PICK_REDIRECT_APP
                                },
                                onPickWidget = { mins, customLabel ->
                                    widgetMinutes = mins
                                    widgetCustomLabel = customLabel.trim()
                                    widgetError = null
                                    screen = MainScreen.PICK_WIDGET
                                },
                                onConfigureWidget = { task ->
                                    reconfigureWidgetTask(task)
                                },
                                onDeleteWidget = { widgetId ->
                                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                        runCatching { widgetHost.deleteAppWidgetId(widgetId) }
                                    }
                                },
                                onExit = { screen = MainScreen.HOME },
                            )
                            MainScreen.HOME -> GameApp(store, control) {
                                GateSection(
                                    store = store,
                                    onApps = { screen = MainScreen.APPS },
                                    onTasks = { screen = MainScreen.TASKS },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    @Deprecated("Required for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE_WIDGET) {
            handleWidgetConfigResult(resultCode, data)
        }
    }

    /** Allocates a host ID, binds the chosen widget (prompting Android's bind consent if needed), and configures it. */
    private fun onSelectWidgetProvider(info: AppWidgetProviderInfo, minutes: Int, customLabel: String) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetMinutes = minutes
        pendingWidgetCustomLabel = customLabel.trim()
        pendingWidgetReconfigure = false

        val mgr = AppWidgetManager.getInstance(this)
        val bound = runCatching { mgr.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (bound) {
            proceedAfterWidgetBound(id)
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            runCatching {
                bindWidgetLauncher.launch(bindIntent)
            }.onFailure {
                runCatching { widgetHost.deleteAppWidgetId(id) }
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                widgetError = "Widget konnte auf diesem Gerät nicht gebunden werden."
            }
        }
    }

    private fun proceedAfterWidgetBound(widgetId: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (info == null) {
            runCatching { widgetHost.deleteAppWidgetId(widgetId) }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetReconfigure = false
            widgetError = "Widget-Informationen konnten nicht geladen werden."
            return
        }
        if (info.configure != null) {
            launchWidgetConfiguration(widgetId, info, isReconfigure = false)
        } else {
            saveBoundWidgetTask(widgetId)
        }
    }

    private fun reconfigureWidgetTask(task: GateTask) {
        if (task.widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(task.widgetId) ?: return
        if (info.configure != null) {
            launchWidgetConfiguration(task.widgetId, info, isReconfigure = true)
        }
    }

    private fun launchWidgetConfiguration(widgetId: Int, info: AppWidgetProviderInfo, isReconfigure: Boolean) {
        pendingWidgetId = widgetId
        pendingWidgetReconfigure = isReconfigure
        // On Android 14+ (API 34+), AppWidgetHost needs explicit BAL opt-in to start another app's config Activity.
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        val launchedViaHost = runCatching {
            widgetHost.startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQ_CONFIGURE_WIDGET, options)
        }.isSuccess
        if (!launchedViaHost) {
            val fallbackLaunched = runCatching {
                val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(info.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                configureWidgetLauncher.launch(configIntent)
            }.isSuccess
            if (!fallbackLaunched) {
                if (!isReconfigure) {
                    saveBoundWidgetTask(widgetId)
                } else {
                    pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                    pendingWidgetReconfigure = false
                }
            }
        }
    }

    private fun handleWidgetConfigResult(resultCode: Int, data: Intent?) {
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        val isReconfig = pendingWidgetReconfigure
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            requestWidgetUpdate(this, id)
        }
        if (isReconfig) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetReconfigure = false
            tasksRevision++
            return
        }
        if (resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            saveBoundWidgetTask(id)
        } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { widgetHost.deleteAppWidgetId(id) }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetReconfigure = false
        }
    }

    private fun saveBoundWidgetTask(widgetId: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        val pkg = info?.provider?.packageName.orEmpty()
        val providerLabel = info?.loadLabel(packageManager)?.toString().orEmpty()
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val label = pendingWidgetCustomLabel.ifBlank {
            if (providerLabel.isNotBlank() && !providerLabel.equals(appName, ignoreCase = true)) {
                "$appName · $providerLabel"
            } else {
                providerLabel.ifBlank { appName.ifBlank { "Widget" } }
            }
        }
        val created = GateTask(
            id = "w_${System.currentTimeMillis()}_$widgetId",
            text = label,
            minutes = pendingWidgetMinutes.coerceIn(GateTask.MIN_MINUTES, GateTask.MAX_MINUTES),
            kind = GateTaskKind.WIDGET,
            targetPackage = pkg,
            widgetId = widgetId,
        )
        requestWidgetUpdate(this, widgetId)
        store.gateTasks = store.gateTasks + created
        store.gateActiveTaskIds = store.gateActiveTaskIds + created.id
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingWidgetCustomLabel = ""
        pendingWidgetReconfigure = false
        widgetError = null
        tasksRevision++
        screen = MainScreen.TASKS
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
        ChordPlayer.stop()
    }

    companion object {
        private const val REQ_CONFIGURE_WIDGET = 9001
    }
}

/** The app-gate block at the bottom of the home screen, with One-Sec-style permission setup. */
@Composable
private fun ColumnScope.GateSection(store: Store, onApps: () -> Unit, onTasks: () -> Unit) {
    val context = LocalContext.current
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var gateOn by remember { mutableStateOf(gateEnabled(context)) }
    var required by remember { mutableIntStateOf(store.required) }
    var gatePoolSize by remember { mutableIntStateOf(store.gatePoolSize) }
    var gateKeyMode by remember { mutableStateOf(store.gateKeyMode) }
    var gateRelockMode by remember { mutableStateOf(store.gateRelockMode) }
    var minutes by remember { mutableIntStateOf(store.unlockMinutes) }
    var gateMode by remember { mutableStateOf(store.gateMode) }

    // Re-check both system permissions whenever the user returns from Android Settings.
    LifecycleResumeEffect(Unit) {
        overlayOn = Settings.canDrawOverlays(context)
        gateOn = gateEnabled(context)
        onPauseOrDispose {}
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("App-Sperre", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

    if (overlayOn && gateOn) {
        Text("Aktiv · ${store.blocked.size} App(s) gesperrt", color = Good, fontWeight = FontWeight.SemiBold)
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Berechtigungen einrichten", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                // Step 1: Display over other apps (SYSTEM_ALERT_WINDOW) — ensures GateActivity opens reliably.
                PermissionRow(
                    title = "1. Über anderen Apps einblenden",
                    granted = overlayOn,
                    actionLabel = "Erlauben",
                    onAction = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    },
                )

                // Step 2: Accessibility service — detects when a blocked app comes to the foreground.
                PermissionRow(
                    title = "2. Bedienungshilfe aktivieren",
                    granted = gateOn,
                    actionLabel = "Öffnen",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )

                if (!gateOn) {
                    Text(
                        "Falls ausgegraut (Android 13+): Erst App-Info öffnen → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("App-Info öffnen")
                    }
                }
            }
        }
    }

    OutlinedButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) { Text("Gesperrte Apps wählen") }
    val activeTaskCount = store.activeGateTasks().size
    val poolSummary = when {
        store.gateIncludeQuiz && activeTaskCount > 0 -> "Gehörbildung + $activeTaskCount Aufgabe(n)"
        activeTaskCount > 0 -> "$activeTaskCount eigene Aufgabe(n)"
        else -> "Nur Gehörbildung"
    }
    OutlinedButton(onClick = onTasks, modifier = Modifier.fillMaxWidth()) {
        Text("Sperr-Aufgaben: $poolSummary")
    }
    Text("Modus der App-Sperre", fontSize = 14.sp)
    ModePicker(selected = gateMode, onSelect = { gateMode = it; store.gateMode = it })
    Stepper("Wechselnde Aufgaben pro Sperre", gatePoolSize, 2..18) { gatePoolSize = it; store.gatePoolSize = it }
    Text("Tonart pro Sperre", fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GateKeyMode.entries.forEach { option ->
            val selected = option == gateKeyMode
            Button(
                onClick = { gateKeyMode = option; store.gateKeyMode = option },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                ),
                modifier = Modifier.weight(1f).height(38.dp),
            ) {
                Text(
                    option.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
    Text(
        when (gateKeyMode) {
            GateKeyMode.SINGLE_KEY_TONALITY -> "Nur eine Tonart (z. B. nur F-Dur oder nur F-Moll) – wechselt bei jedem Öffnen"
            GateKeyMode.SAME_ROOT -> "Gleicher Grundton (z. B. F-Dur & F-Moll) – wechselt bei jedem Öffnen"
            GateKeyMode.RANDOM -> "Jede Aufgabe in beliebiger Tonart (Dur & Moll gemischt)"
        },
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.72f),
    )
    Stepper("Richtige zum Öffnen", required, 1..20) { required = it; store.required = it }
    Text("Wieder sperren", fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GateRelockMode.entries.forEach { option ->
            val selected = option == gateRelockMode
            Button(
                onClick = {
                    gateRelockMode = option
                    store.gateRelockMode = option
                    // Clear stale unlocks when switching to stricter relock modes.
                    if (option != GateRelockMode.TIMER) store.clearAllUnlocks()
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                ),
                modifier = Modifier.weight(1f).height(38.dp),
            ) {
                Text(
                    option.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
    Text(
        when (gateRelockMode) {
            GateRelockMode.TIMER -> "Bleibt nach dem Lösen für $minutes Min. offen"
            GateRelockMode.ON_SCREEN_OFF -> "Sperrt wieder, sobald du das Display ausschaltest"
            GateRelockMode.ON_APP_LEAVE -> "Sperrt sofort wieder, sobald du die App verlässt oder das Display ausschaltest"
        },
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.72f),
    )
    if (gateRelockMode == GateRelockMode.TIMER) {
        Stepper("Minuten offen danach", minutes, 1..60) { minutes = it; store.unlockMinutes = it }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (granted) "✓" else "✗",
            color = if (granted) Good else Bad,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(title, Modifier.weight(1f), fontSize = 14.sp)
        if (!granted) {
            Button(onClick = onAction) { Text(actionLabel, fontSize = 13.sp) }
        }
    }
}

private class AppEntry(val pkg: String, val label: String)

private class WidgetProviderEntry(
    val info: AppWidgetProviderInfo,
    val key: String,
    val appName: String,
    val widgetLabel: String,
    val configurable: Boolean,
)

/**
 * Loads launchable apps installed on the device and disambiguates duplicate display names
 * (e.g. multiple Anki variants) using the last package segment so search results stay clear.
 */
private fun loadInstalledLauncherApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val raw = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map {
            val label = it.loadLabel(pm).toString().trim().ifEmpty { it.activityInfo.packageName }
            AppEntry(it.activityInfo.packageName, label)
        }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
    val labelCounts = raw.groupingBy { it.label.lowercase() }.eachCount()
    return raw.map { entry ->
        if ((labelCounts[entry.label.lowercase()] ?: 0) > 1) {
            AppEntry(entry.pkg, "${entry.label} (${entry.pkg.substringAfterLast('.')})")
        } else {
            entry
        }
    }
}

@Composable
private fun ColumnScope.Apps(store: Store, onExit: () -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blocked) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val apps = remember {
        loadInstalledLauncherApps(context)
            .sortedWith(compareBy({ it.pkg !in store.blocked }, { it.label.lowercase() }))
    }
    val filtered = remember(apps, query) {
        AppSearch.filterAndRank(apps, query, labelOf = { it.label })
    }
    // Reset scroll to the top match whenever the search query changes so LazyColumn doesn't jump to anchored items.
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Text("Gesperrte Apps", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("App suchen (z. B. WhatsApp)…") },
        singleLine = true,
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { query = "" }) { Text("×", fontSize = 20.sp) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Keine App gefunden",
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            val toggle = {
                blocked = if (app.pkg in blocked) blocked - app.pkg else blocked + app.pkg
                store.blocked = blocked
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = app.pkg in blocked, onCheckedChange = { toggle() })
                Text(app.label)
            }
        }
    }
    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Fertig") }
}

/**
 * Submenu to create timed text tasks, app redirects, or configured widgets (1..3 min) and
 * move them into or out of the active app-gate rotation field. By default only the ear-training
 * quiz is active; adding custom entries makes the gate alternate between all active entries.
 */
@Composable
private fun ColumnScope.GateTasksScreen(
    store: Store,
    revision: Int,
    onPickApp: (minutes: Int) -> Unit,
    onPickWidget: (minutes: Int, customLabel: String) -> Unit,
    onConfigureWidget: (GateTask) -> Unit,
    onDeleteWidget: (widgetId: Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    var tasks by remember(revision) { mutableStateOf(store.gateTasks) }
    var activeIds by remember(revision) { mutableStateOf(store.gateActiveTaskIds) }
    var includeQuiz by remember(revision) { mutableStateOf(store.gateIncludeQuiz) }
    var newText by remember { mutableStateOf("") }
    var newMinutes by remember { mutableIntStateOf(1) }

    val activeTasks = tasks.filter { it.id in activeIds }
    val inactiveTasks = tasks.filter { it.id !in activeIds }
    val totalActiveCount = (if (includeQuiz) 1 else 0) + activeTasks.size

    fun taskKindBadge(task: GateTask): String = when (task.kind) {
        GateTaskKind.TEXT -> "📝 ${task.minutes} Min. · Text-Aufgabe"
        GateTaskKind.APP -> "📱 ${task.minutes} Min. · App-Weiterleitung"
        GateTaskKind.WIDGET -> "🧩 ${task.minutes} Min. · Widget"
    }

    fun isConfigurableWidget(task: GateTask): Boolean =
        task.kind == GateTaskKind.WIDGET &&
            task.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            widgetManager.getAppWidgetInfo(task.widgetId)?.configure != null

    fun persist(nextTasks: List<GateTask> = tasks, nextActive: Set<String> = activeIds, nextQuiz: Boolean = includeQuiz) {
        val validIds = nextTasks.map { it.id }.toSet()
        val cleanedActive = nextActive.intersect(validIds)
        // Always keep at least one active entry (fall back to ear-training if all tasks are removed).
        val safeQuiz = if (cleanedActive.isEmpty()) true else nextQuiz
        tasks = nextTasks
        activeIds = cleanedActive
        includeQuiz = safeQuiz
        store.gateTasks = nextTasks
        store.gateActiveTaskIds = cleanedActive
        store.gateIncludeQuiz = safeQuiz
    }

    fun cycleMinutes(task: GateTask) {
        val nextMin = if (task.minutes >= GateTask.MAX_MINUTES) GateTask.MIN_MINUTES else task.minutes + 1
        persist(nextTasks = tasks.map { if (it.id == task.id) it.copy(minutes = nextMin) else it })
    }

    fun deleteTask(task: GateTask) {
        if (task.kind == GateTaskKind.WIDGET) onDeleteWidget(task.widgetId)
        persist(nextTasks = tasks.filterNot { it.id == task.id })
    }

    Text("Sperr-Aufgaben", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(
        "Erstelle Text-Aufgaben, App-Weiterleitungen oder Widgets (1–3 Min.) – Dauer per Tipp anpassbar.",
        fontSize = 13.sp,
        color = Color.White.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )

    LazyColumn(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Active rotation field (defaults to only ear-training).
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Aktiv in der Sperre ($totalActiveCount)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (includeQuiz) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("🎵 Gehörbildung (aktueller Modus)", Modifier.weight(1f), fontSize = 14.sp)
                            if (totalActiveCount > 1) {
                                TextButton(
                                    onClick = { persist(nextQuiz = false) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                                ) { Text("Raus", fontSize = 13.sp) }
                            }
                        }
                    }
                    activeTasks.forEach { task ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${taskKindBadge(task)} ↻",
                                    fontSize = 12.sp,
                                    color = Good,
                                    modifier = Modifier.clickable { cycleMinutes(task) },
                                )
                            }
                            if (isConfigurableWidget(task)) {
                                TextButton(
                                    onClick = { onConfigureWidget(task) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                                ) { Text("⚙", fontSize = 16.sp) }
                            }
                            TextButton(
                                onClick = { persist(nextActive = activeIds - task.id) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                            ) { Text("Raus", fontSize = 13.sp) }
                            TextButton(
                                onClick = { deleteTask(task) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                            ) { Text("×", color = Bad, fontSize = 18.sp) }
                        }
                    }
                }
            }
        }

        // Create a new 1..3 min task (Text, App redirect, or configured Widget).
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Neu hinzufügen (1–3 Min.)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (GateTask.MIN_MINUTES..GateTask.MAX_MINUTES).forEach { min ->
                            val selected = min == newMinutes
                            Button(
                                onClick = { newMinutes = min },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                ),
                                modifier = Modifier.weight(1f).height(36.dp),
                            ) {
                                Text("$min Min.", fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onPickApp(newMinutes) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("📱 + App", fontSize = 13.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                val label = newText.trim()
                                newText = ""
                                onPickWidget(newMinutes, label)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🧩 + Widget", fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        placeholder = { Text("Text-Aufgabe (oder optional Widget-Name)…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val trimmed = newText.trim()
                            if (trimmed.isNotEmpty()) {
                                val created = GateTask(
                                    id = "t_${System.currentTimeMillis()}_${tasks.size}",
                                    text = trimmed,
                                    minutes = newMinutes,
                                )
                                persist(nextTasks = tasks + created, nextActive = activeIds + created.id)
                                newText = ""
                            }
                        },
                        enabled = newText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("📝 + Text-Aufgabe speichern")
                    }
                }
            }
        }

        // Available items that can be moved into the active field.
        if (!includeQuiz || inactiveTasks.isNotEmpty()) {
            item {
                Text(
                    "Verfügbar (pausiert)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!includeQuiz) {
                item(key = GateChallenge.QUIZ_ID) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎵 Gehörbildung (aktueller Modus)", Modifier.weight(1f), fontSize = 14.sp)
                        Button(onClick = { persist(nextQuiz = true) }) { Text("+ Aktiv", fontSize = 13.sp) }
                    }
                }
            }
            items(inactiveTasks, key = { it.id }) { task ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.text,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${taskKindBadge(task)} ↻",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { cycleMinutes(task) },
                        )
                    }
                    if (isConfigurableWidget(task)) {
                        TextButton(
                            onClick = { onConfigureWidget(task) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                        ) { Text("⚙", fontSize = 16.sp) }
                    }
                    Button(
                        onClick = { persist(nextActive = activeIds + task.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("+ Aktiv", fontSize = 13.sp) }
                    TextButton(
                        onClick = { deleteTask(task) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                    ) {
                        Text("×", color = Bad, fontSize = 18.sp)
                    }
                }
            }
        }
    }
    Button(onClick = onExit, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Fertig") }
}

/** Searchable app picker to create a 1..3 min practice-app redirect (e.g. Widder, Anki, Duolingo). */
@Composable
private fun ColumnScope.RedirectAppPicker(
    minutes: Int,
    onPick: (AppEntry) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val apps = remember {
        loadInstalledLauncherApps(context).sortedBy { it.label.lowercase() }
    }
    val filtered = remember(apps, query) {
        AppSearch.filterAndRank(apps, query, labelOf = { it.label })
    }
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Text("App zum Üben ($minutes Min.)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("App suchen (z. B. Widder)…") },
        singleLine = true,
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { query = "" }) { Text("×", fontSize = 20.sp) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Keine App gefunden",
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(app) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(app.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("$minutes Min. →", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
}

/**
 * Searchable in-app Android widget picker. Lists every installed AppWidget provider so HolzAffe
 * can explicitly request bind consent (`ACTION_APPWIDGET_BIND`) and launch the widget's own
 * configuration Activity without relying on the broken legacy Settings picker.
 */
@Composable
private fun ColumnScope.WidgetPicker(
    minutes: Int,
    errorMessage: String?,
    onPick: (WidgetProviderEntry) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val entries = remember {
        val pm = context.packageManager
        val mgr = AppWidgetManager.getInstance(context)
        mgr.installedProviders
            .map { info ->
                val pkg = info.provider.packageName
                val appName = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
                }.getOrDefault(pkg).ifEmpty { pkg }
                val rawWidgetLabel = runCatching {
                    info.loadLabel(pm)?.toString()?.trim().orEmpty()
                }.getOrDefault("")
                val widgetLabel = rawWidgetLabel.ifBlank { "Widget" }
                WidgetProviderEntry(
                    info = info,
                    key = info.provider.flattenToString(),
                    appName = appName,
                    widgetLabel = widgetLabel,
                    configurable = info.configure != null,
                )
            }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.widgetLabel.lowercase() }))
    }
    val filtered = remember(entries, query) {
        AppSearch.filterAndRank(
            entries,
            query,
            labelOf = { it.appName },
            secondaryOf = { it.widgetLabel },
        )
    }
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Text("Widget wählen ($minutes Min.)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (!errorMessage.isNullOrBlank()) {
        Text(
            errorMessage,
            fontSize = 13.sp,
            color = Bad,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Widget suchen (z. B. Widder, Eröffnung, Wassertiger)…") },
        singleLine = true,
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { query = "" }) { Text("×", fontSize = 20.sp) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Kein Widget gefunden",
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.key }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(entry) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.appName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.widgetLabel,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (entry.configurable) "⚙ Konfigurierbar" else "Direkt",
                    fontSize = 12.sp,
                    color = if (entry.configurable) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
}

private fun gateEnabled(context: Context): Boolean {
    val me = ComponentName(context, GateService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled.orEmpty().split(':').any { it.equals(me, ignoreCase = true) }
}
