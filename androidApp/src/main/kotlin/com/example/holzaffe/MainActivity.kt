package com.example.holzaffe

import android.app.ActivityOptions
import android.app.TimePickerDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.delay

private enum class OverlayScreen { NONE, GATE_SETTINGS, APPS, EXERCISES, PICK_REDIRECT_APP, PICK_WIDGET, SCHEDULE, EDIT_SLOT }

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var widgetHost: AppWidgetHost
    private val control = RunControl()

    private var overlayScreen by mutableStateOf(OverlayScreen.NONE)
    private var editingSlotId by mutableStateOf<String?>(null)
    private var scheduleReturnToSettings by mutableStateOf(false)
    private var widgetMinutes by mutableIntStateOf(1)
    private var widgetError by mutableStateOf<String?>(null)

    // Pending widget metadata while Android's bind permission / widget configurator runs.
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetMinutes: Int = 1
    private var pendingWidgetCustomLabel: String = ""
    private var pendingWidgetReconfigure: Boolean = false

    // Bumped whenever a gate task or widget is added/reconfigured so Compose tabs reload Store.
    private var tasksRevision by mutableIntStateOf(0)
    // Bumped whenever the gate pause timer or weekly time slots change.
    private var scheduleRevision by mutableIntStateOf(0)

    private var pendingAudioCallback: ((ImportedAudio?) -> Unit)? = null

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingAudioCallback ?: return@registerForActivityResult
        pendingAudioCallback = null
        if (uri == null) {
            callback(null)
            return@registerForActivityResult
        }
        // Copy off the main thread: recordings can be a few MB.
        Thread {
            val imported = runCatching { importAudio(uri) }.getOrNull()
            runOnUiThread { callback(imported) }
        }.start()
    }

    /** Uploaded recordings live in private app storage, so the app gate can play them without permissions. */
    private val audioStorage = object : AudioStorage {
        override val formatsHint = "MP3, M4A, WAV, OGG und andere gängige Formate funktionieren."

        override fun pick(onPicked: (ImportedAudio?) -> Unit) {
            pendingAudioCallback = onPicked
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        override fun delete(path: String) {
            runCatching { File(path).takeIf { it.parentFile == audioDir() }?.delete() }
        }
    }

    private fun audioDir(): File = File(filesDir, "audio").apply { mkdirs() }

    private fun importAudio(uri: Uri): ImportedAudio {
        var displayName = ""
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0).orEmpty()
        }
        val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: "audio"
        val target = File(audioDir(), "${UUID.randomUUID()}.$ext")
        val input = contentResolver.openInputStream(uri) ?: error("cannot open $uri")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
        return ImportedAudio(target.absolutePath, displayName.substringBeforeLast('.'))
    }

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
                    val context = LocalContext.current
                    var redirectMinutes by remember { mutableIntStateOf(1) }
                    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
                    var gateOn by remember { mutableStateOf(gateEnabled(context)) }
                    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(15_000L)
                            nowTick = System.currentTimeMillis()
                        }
                    }

                    // Re-check system permissions & schedule clock whenever returning to the app.
                    LifecycleResumeEffect(Unit) {
                        overlayOn = Settings.canDrawOverlays(context)
                        gateOn = gateEnabled(context)
                        nowTick = System.currentTimeMillis()
                        onPauseOrDispose {}
                    }

                    val scheduleStatus = remember(scheduleRevision, nowTick) {
                        store.gateScheduleStatus(nowEpochMs = nowTick)
                    }
                    val onScheduleChanged = {
                        nowTick = System.currentTimeMillis()
                        scheduleRevision++
                        if (!store.isGateCurrentlyEnforced()) {
                            GateService.cancelActiveRedirect()
                        }
                    }
                    val openSchedule = { fromSettings: Boolean ->
                        scheduleReturnToSettings = fromSettings
                        nowTick = System.currentTimeMillis()
                        overlayScreen = OverlayScreen.SCHEDULE
                    }

                    BackHandler(enabled = overlayScreen != OverlayScreen.NONE || control.canGoBack) {
                        if (overlayScreen != OverlayScreen.NONE) {
                            overlayScreen = when (overlayScreen) {
                                OverlayScreen.EDIT_SLOT -> OverlayScreen.SCHEDULE
                                OverlayScreen.SCHEDULE ->
                                    if (scheduleReturnToSettings) OverlayScreen.GATE_SETTINGS else OverlayScreen.NONE
                                OverlayScreen.APPS -> OverlayScreen.GATE_SETTINGS
                                OverlayScreen.PICK_REDIRECT_APP,
                                OverlayScreen.PICK_WIDGET,
                                OverlayScreen.EXERCISES,
                                OverlayScreen.GATE_SETTINGS,
                                OverlayScreen.NONE,
                                -> OverlayScreen.NONE
                            }
                            widgetError = null
                        } else {
                            control.back()
                        }
                    }

                    Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                        when (overlayScreen) {
                            OverlayScreen.GATE_SETTINGS -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                GateSettingsScreen(
                                    store = store,
                                    overlayOn = overlayOn,
                                    gateOn = gateOn,
                                    scheduleStatus = scheduleStatus,
                                    scheduleRevision = scheduleRevision,
                                    nowMs = nowTick,
                                    onApps = { overlayScreen = OverlayScreen.APPS },
                                    onSchedule = { openSchedule(true) },
                                    onScheduleChanged = onScheduleChanged,
                                    onBack = { overlayScreen = OverlayScreen.NONE },
                                )
                            }

                            OverlayScreen.SCHEDULE -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                GateScheduleScreen(
                                    store = store,
                                    scheduleStatus = scheduleStatus,
                                    scheduleRevision = scheduleRevision,
                                    nowMs = nowTick,
                                    onAddSlot = {
                                        editingSlotId = null
                                        overlayScreen = OverlayScreen.EDIT_SLOT
                                    },
                                    onEditSlot = { slotId ->
                                        editingSlotId = slotId
                                        overlayScreen = OverlayScreen.EDIT_SLOT
                                    },
                                    onScheduleChanged = onScheduleChanged,
                                    onBack = {
                                        overlayScreen = if (scheduleReturnToSettings) {
                                            OverlayScreen.GATE_SETTINGS
                                        } else {
                                            OverlayScreen.NONE
                                        }
                                    },
                                )
                            }

                            OverlayScreen.EDIT_SLOT -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                GateSlotEditorScreen(
                                    store = store,
                                    slotId = editingSlotId,
                                    onSaved = {
                                        onScheduleChanged()
                                        overlayScreen = OverlayScreen.SCHEDULE
                                    },
                                    onDeleted = {
                                        onScheduleChanged()
                                        overlayScreen = OverlayScreen.SCHEDULE
                                    },
                                    onCancel = { overlayScreen = OverlayScreen.SCHEDULE },
                                )
                            }

                            OverlayScreen.APPS -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Apps(store, onExit = { overlayScreen = OverlayScreen.GATE_SETTINGS })
                            }

                            OverlayScreen.EXERCISES -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                GateExercisesScreen(store, onExit = { overlayScreen = OverlayScreen.NONE })
                            }

                            OverlayScreen.PICK_REDIRECT_APP -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                RedirectAppPicker(
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
                                        control.tab = AppTab.REDIRECTS
                                        overlayScreen = OverlayScreen.NONE
                                    },
                                    onCancel = { overlayScreen = OverlayScreen.NONE },
                                )
                            }

                            OverlayScreen.PICK_WIDGET -> Column(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                WidgetPicker(
                                    minutes = widgetMinutes,
                                    errorMessage = widgetError,
                                    onPick = { entry, customLabel ->
                                        widgetError = null
                                        onSelectWidgetProvider(entry.info, widgetMinutes, customLabel)
                                    },
                                    onCancel = {
                                        widgetError = null
                                        overlayScreen = OverlayScreen.NONE
                                    },
                                )
                            }

                            OverlayScreen.NONE -> GameApp(
                                scores = store,
                                control = control,
                                audioStorage = audioStorage,
                                showAndroidTabs = true,
                                topBarExtra = {
                                    GateStatusPill(
                                        active = overlayOn && gateOn,
                                        blockedCount = store.blocked.size,
                                        scheduleStatus = scheduleStatus,
                                        nowMs = nowTick,
                                        onClick = { overlayScreen = OverlayScreen.GATE_SETTINGS },
                                    )
                                },
                                runsGateContent = { libRevision ->
                                    GateQuizSectionCard(
                                        store = store,
                                        libraryRevision = libRevision,
                                        tasksRevision = tasksRevision,
                                        onExercises = { overlayScreen = OverlayScreen.EXERCISES },
                                        onCreateRun = { control.page = GamePage.EditRun(null) },
                                        onEditRun = { id -> control.page = GamePage.EditRun(id) },
                                        onChanged = { tasksRevision++ },
                                    )
                                },
                                tasksTabContent = {
                                    AufgabenTab(
                                        store = store,
                                        revision = tasksRevision,
                                        overlayOn = overlayOn,
                                        gateOn = gateOn,
                                        scheduleStatus = scheduleStatus,
                                        scheduleRevision = scheduleRevision,
                                        nowMs = nowTick,
                                        onOpenGateSettings = { overlayScreen = OverlayScreen.GATE_SETTINGS },
                                        onOpenSchedule = { openSchedule(false) },
                                        onChanged = { tasksRevision++ },
                                    )
                                },
                                redirectsTabContent = {
                                    WeiterleitungenTab(
                                        store = store,
                                        revision = tasksRevision,
                                        overlayOn = overlayOn,
                                        gateOn = gateOn,
                                        scheduleStatus = scheduleStatus,
                                        scheduleRevision = scheduleRevision,
                                        nowMs = nowTick,
                                        onPickApp = { mins ->
                                            redirectMinutes = mins
                                            overlayScreen = OverlayScreen.PICK_REDIRECT_APP
                                        },
                                        onPickWidget = { mins ->
                                            widgetMinutes = mins
                                            widgetError = null
                                            overlayScreen = OverlayScreen.PICK_WIDGET
                                        },
                                        onConfigureWidget = { task -> reconfigureWidgetTask(task) },
                                        onDeleteWidget = { widgetId ->
                                            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                                runCatching { widgetHost.deleteAppWidgetId(widgetId) }
                                            }
                                        },
                                        onOpenGateSettings = { overlayScreen = OverlayScreen.GATE_SETTINGS },
                                        onOpenSchedule = { openSchedule(false) },
                                        onChanged = { tasksRevision++ },
                                    )
                                },
                                settingsExtra = {
                                    SectionCard {
                                        Text("App-Sperre", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                                        NavRow(
                                            title = "Berechtigungen, Apps & Wieder-Sperren",
                                            subtitle = if (overlayOn && gateOn) {
                                                "Aktiv · ${store.blocked.size} App(s) gesperrt"
                                            } else {
                                                "Berechtigungen müssen noch eingerichtet werden"
                                            },
                                            badge = if (overlayOn && gateOn) "Aktiv" else "Setup",
                                            onClick = { overlayScreen = OverlayScreen.GATE_SETTINGS },
                                        )
                                        val activeSlotCount = remember(scheduleRevision) {
                                            if (store.gateScheduleEnabled) store.gateTimeSlots.count { it.enabled } else 0
                                        }
                                        val sub = scheduleSubline(scheduleStatus, nowTick)
                                        NavRow(
                                            title = "Zeitplan & Sperr-Pause",
                                            subtitle = if (sub.isNotBlank()) {
                                                "${scheduleHeadline(scheduleStatus, nowTick)} · $sub"
                                            } else {
                                                scheduleHeadline(scheduleStatus, nowTick)
                                            },
                                            badge = scheduleBadge(scheduleStatus, activeSlotCount),
                                            onClick = { openSchedule(false) },
                                        )
                                    }
                                },
                            )
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
        control.tab = AppTab.REDIRECTS
        overlayScreen = OverlayScreen.NONE
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

/** Compact lock pill in the top bar showing gate status and opening [GateSettingsScreen]. */
@Composable
private fun GateStatusPill(
    active: Boolean,
    blockedCount: Int,
    scheduleStatus: GateScheduleStatus,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val enforced = active && scheduleStatus.enforced
    val bg = if (enforced) AppColors.GoodSoft else AppColors.AccentGoldSoft
    val fg = if (enforced) Good else AppColors.AccentGold
    val icon = if (active && !scheduleStatus.enforced) AppIcons.Clock else AppIcons.Lock
    val label = pillLabel(active, blockedCount, scheduleStatus, nowMs)
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PermissionBanner(onOpenGateSettings: () -> Unit) {
    SectionCard(highlighted = true, padding = PaddingValues(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGateSettings),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Lock, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("App-Sperre noch nicht aktiv", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.AccentGold)
                Text("Tippe hier, um Berechtigungen & gesperrte Apps einzurichten.", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(18.dp))
        }
    }
}

/** Summary pill showing active gate challenges and current schedule/pause state. */
@Composable
private fun RotationSummaryCard(
    store: Store,
    revision: Int,
    scheduleStatus: GateScheduleStatus,
    scheduleRevision: Int,
    nowMs: Long,
    onOpenSchedule: () -> Unit,
) {
    val includeQuiz = remember(revision) { store.gateIncludeQuiz }
    val activeTasks = remember(revision) { store.activeGateTasks() }
    val hasScheduleSlots = remember(scheduleRevision) { store.gateScheduleEnabled && store.gateTimeSlots.any { it.enabled } }
    val textCount = activeTasks.count { it.kind == GateTaskKind.TEXT }
    val redirectCount = activeTasks.count { it.kind == GateTaskKind.APP || it.kind == GateTaskKind.WIDGET }

    val parts = buildList {
        if (includeQuiz) add("Gehörbildung")
        if (textCount > 0) add("$textCount Aufgabe(n)")
        if (redirectCount > 0) add("$redirectCount Weiterleitung(en)")
    }
    val summary = if (parts.isEmpty()) "Nur Gehörbildung" else parts.joinToString(" · ")
    val scheduleText = rotationScheduleSummary(scheduleStatus, hasScheduleSlots, nowMs)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenSchedule),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.SurfaceCard,
        border = BorderStroke(
            width = 1.dp,
            color = if (!scheduleStatus.enforced) AppColors.AccentGold.copy(alpha = 0.45f) else AppColors.BorderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Aktive Rotation:", fontSize = 12.sp, color = AppColors.TextSecondary)
                Text(
                    summary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.AccentGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (scheduleText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val tint = if (!scheduleStatus.enforced) AppColors.AccentGold else Good
                    Icon(AppIcons.Clock, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                    Text(
                        scheduleText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Embedded inside the `Runs` tab on Android: configures which ear-training runs the app gate asks. */
@Composable
private fun GateQuizSectionCard(
    store: Store,
    libraryRevision: Int,
    tasksRevision: Int,
    onExercises: () -> Unit,
    onCreateRun: () -> Unit,
    onEditRun: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val library = remember { Library(store) }
    val runs = remember(libraryRevision) { library.runs }
    val audios = remember(libraryRevision) { library.audios }
    val customChords = remember(libraryRevision) { library.customChords }
    var selection by remember(libraryRevision) { mutableStateOf(store.gateSelection) }
    var includeQuiz by remember(tasksRevision) { mutableStateOf(store.gateIncludeQuiz) }
    val activeCustomTasks = remember(tasksRevision) { store.activeGateTasks() }
    var required by remember { mutableIntStateOf(store.required) }
    var gatePoolSize by remember { mutableIntStateOf(store.gatePoolSize) }
    var gateKeyMode by remember { mutableStateOf(store.gateKeyMode) }
    var showRules by remember { mutableStateOf(false) }

    val target = remember(selection, libraryRevision) { library.resolveSelection(selection, store.gameMode) }
    val selectedBuiltinModes = remember(target) {
        when (target) {
            is RunTarget.Builtin -> if (target.mode == GameMode.MIX) GameMode.PLAYABLE else listOf(target.mode)
            is RunTarget.Combined -> target.modes
            is RunTarget.Custom -> emptyList()
        }
    }

    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gehörbildung in der App-Sperre", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text(
                    if (includeQuiz) "Aktiv · beim Öffnen einer gesperrten App" else "Pausiert (andere Sperr-Aufgaben aktiv)",
                    fontSize = 12.sp,
                    color = if (includeQuiz) Good else AppColors.TextSecondary,
                )
            }
            Switch(
                checked = includeQuiz,
                enabled = !includeQuiz || activeCustomTasks.isNotEmpty(),
                onCheckedChange = { checked ->
                    if (!checked && activeCustomTasks.isEmpty()) return@Switch
                    includeQuiz = checked
                    store.gateIncludeQuiz = checked
                    onChanged()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnAccent,
                    checkedTrackColor = AppColors.AccentGold,
                ),
            )
        }
        if (includeQuiz && activeCustomTasks.isEmpty()) {
            Text(
                "Tipp: Um Gehörbildung in der Sperre auszuschalten, aktiviere erst eine Aufgabe oder Weiterleitung.",
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
            )
        }

        MultiRunPicker(
            selectedTokens = selection,
            runs = runs,
            itemCountOf = { library.items(it, audios, customChords).size },
            onToggleToken = { token ->
                val next = RunSelectionCodec.toggle(selection, token)
                selection = next
                store.gateSelection = next
            },
            onResetToModes = { next ->
                selection = next
                store.gateSelection = next
            },
            onCreateRun = onCreateRun,
            onLongClickRun = { run -> onEditRun(run.id) },
            onLongClickMode = { onExercises() },
        )

        if (selectedBuiltinModes.isNotEmpty()) {
            val allInSelectedModes = selectedBuiltinModes.flatMap { EXERCISES_BY_MODE.getValue(it) }
            val disabled = store.gateDisabledExerciseIds
            val enabledCount = allInSelectedModes.count { it.id !in disabled }
            NavRow(
                title = "Einzelne Akkorde & Fragen filtern",
                subtitle = if (enabledCount == 0 || enabledCount == allInSelectedModes.size) {
                    "Alle ${allInSelectedModes.size} Aufgaben der gewählten Modi aktiv"
                } else {
                    "$enabledCount von ${allInSelectedModes.size} Aufgaben aktiv"
                },
                onClick = onExercises,
            )
        }

        HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { showRules = !showRules }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sperr-Regeln", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    "$required richtige · max. $gatePoolSize Aufg. · ${gateKeyMode.label}",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
            }
            Icon(
                imageVector = if (showRules) AppIcons.ChevronDown else AppIcons.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        if (showRules) {
            Stepper("Richtige zum Öffnen", required, 1..20) { required = it; store.required = it }
            Stepper("Wechselnde Aufgaben pro Sperre", gatePoolSize, 2..18) { gatePoolSize = it; store.gatePoolSize = it }
            Text("Tonart pro Sperre", fontSize = 13.sp, color = AppColors.TextSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GateKeyMode.entries.forEach { option ->
                    ChipButton(
                        label = option.label,
                        selected = option == gateKeyMode,
                        onClick = { gateKeyMode = option; store.gateKeyMode = option },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                when (gateKeyMode) {
                    GateKeyMode.SINGLE_KEY_TONALITY -> "Nur eine Tonart (z. B. nur F-Dur oder nur F-Moll) – wechselt bei jedem Öffnen"
                    GateKeyMode.SAME_ROOT -> "Gleicher Grundton (z. B. F-Dur & F-Moll) – wechselt bei jedem Öffnen"
                    GateKeyMode.RANDOM -> "Jede Aufgabe in beliebiger Tonart (Dur & Moll gemischt)"
                },
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
        }
    }
}

/** Tab 3 (Android): Timed text practice tasks (1..3 min) for the app gate. */
@Composable
private fun AufgabenTab(
    store: Store,
    revision: Int,
    overlayOn: Boolean,
    gateOn: Boolean,
    scheduleStatus: GateScheduleStatus,
    scheduleRevision: Int,
    nowMs: Long,
    onOpenGateSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onChanged: () -> Unit,
) {
    var tasks by remember(revision) { mutableStateOf(store.gateTasks) }
    var activeIds by remember(revision) { mutableStateOf(store.gateActiveTaskIds) }
    var newText by remember { mutableStateOf("") }
    var newMinutes by remember { mutableIntStateOf(1) }
    var deleteCandidate by remember { mutableStateOf<GateTask?>(null) }

    fun persist(nextTasks: List<GateTask> = tasks, nextActive: Set<String> = activeIds) {
        val validIds = nextTasks.map { it.id }.toSet()
        val cleanedActive = nextActive.intersect(validIds)
        // Always keep at least one active gate entry (fall back to ear-training if all tasks are turned off).
        if (cleanedActive.isEmpty()) store.gateIncludeQuiz = true
        tasks = nextTasks
        activeIds = cleanedActive
        store.gateTasks = nextTasks
        store.gateActiveTaskIds = cleanedActive
        onChanged()
    }

    deleteCandidate?.let { task ->
        ConfirmDeleteDialog(
            title = "Aufgabe löschen?",
            message = "„${task.text}“ wird dauerhaft entfernt.",
            onConfirm = {
                persist(nextTasks = tasks.filterNot { it.id == task.id })
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }

    val textTasks = tasks.filter { it.kind == GateTaskKind.TEXT }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!overlayOn || !gateOn) {
            item { PermissionBanner(onOpenGateSettings) }
        }
        item {
            RotationSummaryCard(
                store = store,
                revision = revision,
                scheduleStatus = scheduleStatus,
                scheduleRevision = scheduleRevision,
                nowMs = nowMs,
                onOpenSchedule = onOpenSchedule,
            )
        }

        item {
            SectionCard {
                Text("Neue Text-Aufgabe", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text(
                    "Erscheint beim Öffnen einer gesperrten App als Timer-Aufgabe (z. B. Tonleitern oder Akkorde üben).",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("z. B. 2 Min. C-Dur über das Griffbrett…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DurationPickerRow(selectedMinutes = newMinutes, onSelect = { newMinutes = it })
                Button(
                    onClick = {
                        val trimmed = newText.trim()
                        if (trimmed.isNotEmpty()) {
                            val created = GateTask(
                                id = "t_${System.currentTimeMillis()}_${tasks.size}",
                                text = trimmed,
                                minutes = newMinutes,
                                kind = GateTaskKind.TEXT,
                            )
                            persist(nextTasks = tasks + created, nextActive = activeIds + created.id)
                            newText = ""
                        }
                    },
                    enabled = newText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufgabe hinzufügen", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (textTasks.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        "Noch keine Text-Aufgaben angelegt.",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        } else {
            items(textTasks, key = { it.id }) { task ->
                val isActive = task.id in activeIds
                SectionCard(highlighted = isActive, padding = PaddingValues(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (isActive) "Aktiv in der Sperre" else "Pausiert",
                                fontSize = 12.sp,
                                color = if (isActive) Good else AppColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = isActive,
                            onCheckedChange = { checked ->
                                val nextActive = if (checked) activeIds + task.id else activeIds - task.id
                                persist(nextActive = nextActive)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppColors.OnAccent,
                                checkedTrackColor = AppColors.AccentGold,
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DurationPickerRow(
                            selectedMinutes = task.minutes,
                            onSelect = { mins ->
                                persist(nextTasks = tasks.map { if (it.id == task.id) it.copy(minutes = mins) else it })
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deleteCandidate = task }, modifier = Modifier.size(36.dp)) {
                            Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Tab 4 (Android): App redirects and configured live Android widgets (1..3 min) for the app gate. */
@Composable
private fun WeiterleitungenTab(
    store: Store,
    revision: Int,
    overlayOn: Boolean,
    gateOn: Boolean,
    scheduleStatus: GateScheduleStatus,
    scheduleRevision: Int,
    nowMs: Long,
    onPickApp: (minutes: Int) -> Unit,
    onPickWidget: (minutes: Int) -> Unit,
    onConfigureWidget: (GateTask) -> Unit,
    onDeleteWidget: (widgetId: Int) -> Unit,
    onOpenGateSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    var tasks by remember(revision) { mutableStateOf(store.gateTasks) }
    var activeIds by remember(revision) { mutableStateOf(store.gateActiveTaskIds) }
    var newMinutes by remember { mutableIntStateOf(1) }
    var deleteCandidate by remember { mutableStateOf<GateTask?>(null) }

    fun isConfigurableWidget(task: GateTask): Boolean =
        task.kind == GateTaskKind.WIDGET &&
            task.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            widgetManager.getAppWidgetInfo(task.widgetId)?.configure != null

    fun persist(nextTasks: List<GateTask> = tasks, nextActive: Set<String> = activeIds) {
        val validIds = nextTasks.map { it.id }.toSet()
        val cleanedActive = nextActive.intersect(validIds)
        if (cleanedActive.isEmpty()) store.gateIncludeQuiz = true
        tasks = nextTasks
        activeIds = cleanedActive
        store.gateTasks = nextTasks
        store.gateActiveTaskIds = cleanedActive
        onChanged()
    }

    deleteCandidate?.let { task ->
        ConfirmDeleteDialog(
            title = "Weiterleitung löschen?",
            message = "„${task.text}“ wird dauerhaft entfernt.",
            onConfirm = {
                if (task.kind == GateTaskKind.WIDGET) onDeleteWidget(task.widgetId)
                persist(nextTasks = tasks.filterNot { it.id == task.id })
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }

    val redirects = tasks.filter { it.kind == GateTaskKind.APP || it.kind == GateTaskKind.WIDGET }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!overlayOn || !gateOn) {
            item { PermissionBanner(onOpenGateSettings) }
        }
        item {
            RotationSummaryCard(
                store = store,
                revision = revision,
                scheduleStatus = scheduleStatus,
                scheduleRevision = scheduleRevision,
                nowMs = nowMs,
                onOpenSchedule = onOpenSchedule,
            )
        }

        item {
            SectionCard {
                Text("Neue Weiterleitung", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text(
                    "Leitet beim Öffnen einer gesperrten App erst für 1–3 Min. in eine Lern-App (z. B. Anki, Widder) oder ein konfiguriertes Widget.",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
                DurationPickerRow(selectedMinutes = newMinutes, onSelect = { newMinutes = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPickApp(newMinutes) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.App, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("App wählen", fontSize = 13.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onPickWidget(newMinutes) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.Widget, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Widget wählen", fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }

        if (redirects.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        "Noch keine Weiterleitungen oder Widgets eingerichtet.",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        } else {
            items(redirects, key = { it.id }) { task ->
                val isActive = task.id in activeIds
                val kindIcon = if (task.kind == GateTaskKind.WIDGET) AppIcons.Widget else AppIcons.App
                val kindLabel = if (task.kind == GateTaskKind.WIDGET) "Widget" else "App-Weiterleitung"
                SectionCard(highlighted = isActive, padding = PaddingValues(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(9.dp), color = AppColors.SurfaceCardAlt) {
                            Icon(
                                kindIcon,
                                contentDescription = null,
                                tint = AppColors.AccentGold,
                                modifier = Modifier.padding(7.dp).size(18.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$kindLabel · ${if (isActive) "Aktiv" else "Pausiert"}",
                                fontSize = 12.sp,
                                color = if (isActive) Good else AppColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = isActive,
                            onCheckedChange = { checked ->
                                val nextActive = if (checked) activeIds + task.id else activeIds - task.id
                                persist(nextActive = nextActive)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppColors.OnAccent,
                                checkedTrackColor = AppColors.AccentGold,
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DurationPickerRow(
                            selectedMinutes = task.minutes,
                            onSelect = { mins ->
                                persist(nextTasks = tasks.map { if (it.id == task.id) it.copy(minutes = mins) else it })
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isConfigurableWidget(task)) {
                            IconButton(onClick = { onConfigureWidget(task) }, modifier = Modifier.size(36.dp)) {
                                Icon(AppIcons.Settings, contentDescription = "Konfigurieren", tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { deleteCandidate = task }, modifier = Modifier.size(36.dp)) {
                            Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationPickerRow(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (GateTask.MIN_MINUTES..GateTask.MAX_MINUTES).forEach { min ->
            ChipButton(
                label = "$min Min.",
                selected = min == selectedMinutes,
                onClick = { onSelect(min) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Dedicated App-Sperre setup screen opened from the top-bar lock pill or Settings. */
@Composable
private fun ColumnScope.GateSettingsScreen(
    store: Store,
    overlayOn: Boolean,
    gateOn: Boolean,
    scheduleStatus: GateScheduleStatus,
    scheduleRevision: Int,
    nowMs: Long,
    onApps: () -> Unit,
    onSchedule: () -> Unit,
    onScheduleChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var gateRelockMode by remember { mutableStateOf(store.gateRelockMode) }
    var minutes by remember { mutableIntStateOf(store.unlockMinutes) }
    val activeSlotCount = remember(scheduleRevision) {
        if (store.gateScheduleEnabled) store.gateTimeSlots.count { it.enabled } else 0
    }

    SubScreenTopBar(title = "App-Sperre", onBack = onBack)

    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (overlayOn && gateOn) {
            SectionCard(highlighted = true) {
                val statusTint = if (scheduleStatus.enforced) Good else AppColors.AccentGold
                val statusIcon = if (scheduleStatus.enforced) AppIcons.Lock else AppIcons.Clock
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(scheduleHeadline(scheduleStatus, nowMs), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = statusTint)
                        Text(
                            "${store.blocked.size} App(s) · ${scheduleSubline(scheduleStatus, nowMs)}",
                            fontSize = 12.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                    if (scheduleStatus.reason == GateScheduleReason.PAUSED) {
                        TextButton(
                            onClick = {
                                store.clearGatePause()
                                onScheduleChanged()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Pause beenden", fontSize = 12.sp, color = AppColors.AccentGold, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            SectionCard(highlighted = true) {
                Text("Berechtigungen einrichten", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
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
                        color = AppColors.TextSecondary,
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
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("App-Info öffnen")
                    }
                }
            }
        }

        QuickPauseCard(
            store = store,
            scheduleStatus = scheduleStatus,
            nowMs = nowMs,
            showExtendWhenPaused = false,
            onScheduleChanged = onScheduleChanged,
        )

        SectionCard {
            Text("Zeitplan & Timeslots", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            NavRow(
                title = "Zeitfenster verwalten",
                subtitle = "$activeSlotCount aktive(s) Zeitfenster · ${scheduleHeadline(scheduleStatus, nowMs)}",
                badge = scheduleBadge(scheduleStatus, activeSlotCount),
                onClick = onSchedule,
            )
        }

        SectionCard {
            Text("Gesperrte Apps", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            NavRow(
                title = "Apps zum Sperren auswählen",
                subtitle = "${store.blocked.size} App(s) ausgewählt",
                onClick = onApps,
            )
        }

        SectionCard {
            Text("Wieder sperren", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GateRelockMode.entries.forEach { option ->
                    ChipButton(
                        label = option.label,
                        selected = option == gateRelockMode,
                        onClick = {
                            gateRelockMode = option
                            store.gateRelockMode = option
                            // Clear stale unlocks when switching to stricter relock modes.
                            if (option != GateRelockMode.TIMER) store.clearAllUnlocks()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                when (gateRelockMode) {
                    GateRelockMode.TIMER -> "Bleibt nach dem Lösen für $minutes Min. offen"
                    GateRelockMode.ON_SCREEN_OFF -> "Sperrt wieder, sobald du das Display ausschaltest"
                    GateRelockMode.ON_APP_LEAVE -> "Sperrt sofort wieder, sobald du die App verlässt oder das Display ausschaltest"
                },
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
            if (gateRelockMode == GateRelockMode.TIMER) {
                Stepper("Minuten offen danach", minutes, 1..60) { minutes = it; store.unlockMinutes = it }
            }
        }
    }
}

/**
 * Lets the user choose which chord progressions, cadences, chord types and inversions the app
 * gate asks. Shows the groups matching the built-in modes selected for the app gate.
 */
@Composable
private fun ColumnScope.GateExercisesScreen(store: Store, onExit: () -> Unit) {
    val library = remember { Library(store) }
    val selectedTarget = remember(store.gateSelection) { library.resolveSelection(store.gateSelection, store.gateMode) }
    val activeModes = remember(selectedTarget) {
        when (selectedTarget) {
            is RunTarget.Builtin -> if (selectedTarget.mode == GameMode.MIX) GameMode.PLAYABLE else listOf(selectedTarget.mode)
            is RunTarget.Combined -> selectedTarget.modes
            is RunTarget.Custom -> GameMode.PLAYABLE
        }
    }
    var disabled by remember { mutableStateOf(store.gateDisabledExerciseIds) }
    val groups = remember(activeModes) { activeModes.flatMap { builtinChecklistGroups(it) } }
    val total = groups.sumOf { it.items.size }
    val enabledCount = groups.sumOf { g -> g.items.count { it.id !in disabled } }

    SubScreenTopBar(
        title = "Abgefragte Aufgaben",
        onBack = { ChordPlayer.stop(); onExit() },
    )
    Text(
        "Wähle, welche Akkordfolgen beim Öffnen einer gesperrten App drankommen. " +
            "Pro Sperre werden bis zu ${store.gatePoolSize} davon gezogen. ▶ spielt eine Hörprobe.",
        fontSize = 12.sp,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Text(
        if (enabledCount == 0) {
            "Nichts ausgewählt – dann werden alle $total abgefragt."
        } else {
            "$enabledCount von $total ausgewählt"
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabledCount == 0) Bad else AppColors.AccentGold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ItemChecklist(
        groups = groups,
        isChecked = { it !in disabled },
        onChange = { ids, checked ->
            disabled = if (checked) disabled - ids else disabled + ids
            store.gateDisabledExerciseIds = disabled
        },
        modifier = Modifier.weight(1f),
    )
    Button(
        onClick = { ChordPlayer.stop(); onExit() },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Fertig") }
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
        Text(title, Modifier.weight(1f), fontSize = 14.sp, color = AppColors.TextPrimary)
        if (!granted) {
            Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(actionLabel, fontSize = 13.sp)
            }
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

    SubScreenTopBar(title = "Gesperrte Apps (${blocked.size})", onBack = onExit)
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Keine App gefunden",
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            val checked = app.pkg in blocked
            val toggle = {
                blocked = if (checked) blocked - app.pkg else blocked + app.pkg
                store.blocked = blocked
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = toggle),
                shape = RoundedCornerShape(10.dp),
                color = if (checked) AppColors.AccentGoldSoft else AppColors.SurfaceCard,
                border = BorderStroke(1.dp, if (checked) AppColors.AccentGold.copy(alpha = 0.5f) else AppColors.BorderSubtle),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Text(app.label, color = AppColors.TextPrimary, fontSize = 15.sp)
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

    SubScreenTopBar(title = "App zum Üben ($minutes Min.)", onBack = onCancel)
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Keine App gefunden",
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(app) },
                shape = RoundedCornerShape(12.dp),
                color = AppColors.SurfaceCard,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("$minutes Min. →", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.AccentGold)
                }
            }
        }
    }
    OutlinedButton(
        onClick = onCancel,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Abbrechen") }
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
    onPick: (WidgetProviderEntry, customLabel: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var customLabel by remember { mutableStateOf("") }
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

    SubScreenTopBar(title = "Widget wählen ($minutes Min.)", onBack = onCancel)
    if (!errorMessage.isNullOrBlank()) {
        Text(
            errorMessage,
            fontSize = 13.sp,
            color = Bad,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    OutlinedTextField(
        value = customLabel,
        onValueChange = { customLabel = it },
        label = { Text("Eigener Name (optional)") },
        placeholder = { Text("z. B. Widder · Akkorde") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
    LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Kein Widget gefunden",
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(filtered, key = { it.key }) { entry ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(entry, customLabel.trim()) },
                shape = RoundedCornerShape(12.dp),
                color = AppColors.SurfaceCard,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.appName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                        Text(
                            entry.widgetLabel,
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (entry.configurable) "⚙ Konfigurierbar" else "Direkt",
                        fontSize = 12.sp,
                        color = if (entry.configurable) AppColors.AccentGold else AppColors.TextSecondary,
                    )
                }
            }
        }
    }
    OutlinedButton(
        onClick = onCancel,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Abbrechen") }
}

private fun gateEnabled(context: Context): Boolean {
    val me = ComponentName(context, GateService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled.orEmpty().split(':').any { it.equals(me, ignoreCase = true) }
}

private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun currentIsoDay(nowMs: Long): Int =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).dayOfWeek.value

private fun formatEpochTime(epochMs: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(HH_MM_FORMATTER)

private fun formatPauseUntilLabel(epochMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val targetDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
    val timeStr = targetDt.format(HH_MM_FORMATTER)
    val dayDiff = ChronoUnit.DAYS.between(nowDt.toLocalDate(), targetDt.toLocalDate())
    val targetDay = when {
        dayDiff <= 0L -> "bis $timeStr"
        dayDiff == 1L -> "bis morgen $timeStr"
        else -> "bis ${GateTimeSlot.dayShortLabel(targetDt.dayOfWeek.value)} $timeStr"
    }
    val remaining = GateScheduleEvaluator.formatRemainingMinutes(epochMs - nowMs)
    return "$targetDay ($remaining)"
}

private fun pillLabel(
    active: Boolean,
    blockedCount: Int,
    status: GateScheduleStatus,
    nowMs: Long,
): String {
    if (!active) return "Einrichten"
    if (status.enforced) return "$blockedCount Apps"
    if (status.reason == GateScheduleReason.PAUSED) {
        return "Frei bis ${formatEpochTime(status.pauseUntilEpochMs)}"
    }
    val next = status.nextChange
    val today = currentIsoDay(nowMs)
    return if (next != null && next.dayOfWeek == today && next.minutesUntil < GateTimeSlot.MINUTES_PER_DAY) {
        "Frei bis ${GateTimeSlot.formatMinute(next.minuteOfDay)}"
    } else {
        "Ohne Sperre"
    }
}

private fun scheduleHeadline(status: GateScheduleStatus, nowMs: Long): String = when (status.reason) {
    GateScheduleReason.PAUSED -> "Sperre pausiert"
    GateScheduleReason.ALWAYS_BLOCKED -> "Sperre aktiv (immer)"
    GateScheduleReason.IN_FREE_SLOT ->
        "Ohne Sperre · ${status.matchingSlot?.displayLabel() ?: "Freies Fenster"}"
    GateScheduleReason.OUTSIDE_BLOCK_SLOTS -> "Ohne Sperre"
    GateScheduleReason.IN_BLOCK_SLOT ->
        "Sperre aktiv · ${status.matchingSlot?.displayLabel() ?: "Zeitfenster"}"
    GateScheduleReason.OUTSIDE_FREE_SLOTS -> "Sperre aktiv"
}

private fun scheduleSubline(status: GateScheduleStatus, nowMs: Long): String {
    val today = currentIsoDay(nowMs)
    return when (status.reason) {
        GateScheduleReason.PAUSED ->
            "Ohne Sperre ${formatPauseUntilLabel(status.pauseUntilEpochMs, nowMs)}"
        GateScheduleReason.ALWAYS_BLOCKED ->
            "Keine Zeitfenster aktiv – gesperrte Apps werden jederzeit abgefangen"
        GateScheduleReason.IN_FREE_SLOT ->
            status.nextChange?.let { "Sperre wieder ab ${it.formatRelative(today)}" } ?: "Dauerhaft ohne Sperre"
        GateScheduleReason.OUTSIDE_BLOCK_SLOTS ->
            status.nextChange?.let { "Nächste Sperre ab ${it.formatRelative(today)}" } ?: "Außerhalb der Sperrzeiten"
        GateScheduleReason.IN_BLOCK_SLOT ->
            status.nextChange?.let { "Ohne Sperre ab ${it.formatRelative(today)}" } ?: "Aktuell im Sperr-Zeitfenster"
        GateScheduleReason.OUTSIDE_FREE_SLOTS ->
            status.nextChange?.let { "Ohne Sperre ab ${it.formatRelative(today)}" } ?: "Außerhalb deiner freien Zeiten"
    }
}

private fun rotationScheduleSummary(
    status: GateScheduleStatus,
    hasActiveScheduleSlots: Boolean,
    nowMs: Long,
): String? {
    if (status.enforced && !hasActiveScheduleSlots) return null
    val today = currentIsoDay(nowMs)
    return when (status.reason) {
        GateScheduleReason.PAUSED -> "Pausiert ${formatPauseUntilLabel(status.pauseUntilEpochMs, nowMs)}"
        GateScheduleReason.IN_FREE_SLOT, GateScheduleReason.OUTSIDE_BLOCK_SLOTS -> {
            val next = status.nextChange?.let { " · Sperre ab ${it.formatRelative(today)}" }.orEmpty()
            "Ohne Sperre$next"
        }
        GateScheduleReason.IN_BLOCK_SLOT, GateScheduleReason.OUTSIDE_FREE_SLOTS -> {
            val next = status.nextChange?.let { " · Frei ab ${it.formatRelative(today)}" }.orEmpty()
            "Zeitplan aktiv$next"
        }
        GateScheduleReason.ALWAYS_BLOCKED -> "Zeitplan aus · Immer gesperrt"
    }
}

private fun scheduleBadge(status: GateScheduleStatus, activeSlotCount: Int): String = when {
    status.reason == GateScheduleReason.PAUSED -> "Pausiert"
    !status.enforced -> "Frei"
    activeSlotCount > 0 -> "Aktiv"
    else -> "Immer"
}

private fun showTimePicker(context: Context, initialMinute: Int, onPicked: (Int) -> Unit) {
    val clamped = initialMinute.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1)
    TimePickerDialog(
        context,
        { _, hourOfDay, minute -> onPicked(hourOfDay * 60 + minute) },
        clamped / 60,
        clamped % 60,
        true,
    ).show()
}

/** Shared quick-pause timer card used in both [GateSettingsScreen] and [GateScheduleScreen]. */
@Composable
private fun QuickPauseCard(
    store: Store,
    scheduleStatus: GateScheduleStatus,
    nowMs: Long,
    showExtendWhenPaused: Boolean,
    onScheduleChanged: () -> Unit,
) {
    val context = LocalContext.current
    val isPaused = scheduleStatus.reason == GateScheduleReason.PAUSED
    SectionCard(highlighted = isPaused) {
        Text("Schnell-Pause (Timer)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        if (isPaused) {
            Text(
                "App-Sperre pausiert ${formatPauseUntilLabel(scheduleStatus.pauseUntilEpochMs, nowMs)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.AccentGold,
            )
            if (showExtendWhenPaused) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipButton(
                        label = "+30 Min.",
                        selected = false,
                        onClick = {
                            store.pauseGateForMinutes(30, scheduleStatus.pauseUntilEpochMs)
                            GateService.cancelActiveRedirect()
                            onScheduleChanged()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChipButton(
                        label = "+1 Std.",
                        selected = false,
                        onClick = {
                            store.pauseGateForMinutes(60, scheduleStatus.pauseUntilEpochMs)
                            GateService.cancelActiveRedirect()
                            onScheduleChanged()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChipButton(
                        label = "Bis Uhrzeit",
                        selected = false,
                        onClick = {
                            val endDt = LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(scheduleStatus.pauseUntilEpochMs),
                                ZoneId.systemDefault(),
                            )
                            showTimePicker(context, endDt.hour * 60 + endDt.minute) { picked ->
                                store.pauseGateUntilTime(picked / 60, picked % 60)
                                GateService.cancelActiveRedirect()
                                onScheduleChanged()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Button(
                onClick = {
                    store.clearGatePause()
                    onScheduleChanged()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pause beenden (sofort sperren)", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                "Öffnet gesperrte Apps vorübergehend ohne Herausforderung (z. B. spontan auf der Arbeit).",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30 to "30 Min.", 60 to "1 Std.", 120 to "2 Std.").forEach { (mins, label) ->
                    ChipButton(
                        label = label,
                        selected = false,
                        onClick = {
                            store.pauseGateForMinutes(mins)
                            GateService.cancelActiveRedirect()
                            onScheduleChanged()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
                    val defaultMinute = ((nowDt.hour + 1) % 24) * 60
                    showTimePicker(context, defaultMinute) { picked ->
                        store.pauseGateUntilTime(picked / 60, picked % 60)
                        GateService.cancelActiveRedirect()
                        onScheduleChanged()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(AppIcons.Clock, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bis Uhrzeit pausieren…")
            }
        }
    }
}

/** Manages quick-pause timer and recurring weekly block/free time slots. */
@Composable
private fun ColumnScope.GateScheduleScreen(
    store: Store,
    scheduleStatus: GateScheduleStatus,
    scheduleRevision: Int,
    nowMs: Long,
    onAddSlot: () -> Unit,
    onEditSlot: (String) -> Unit,
    onScheduleChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var slots by remember(scheduleRevision) { mutableStateOf(store.gateTimeSlots) }
    var scheduleEnabled by remember(scheduleRevision) { mutableStateOf(store.gateScheduleEnabled) }
    var deleteCandidate by remember { mutableStateOf<GateTimeSlot?>(null) }

    fun persistSlots(nextSlots: List<GateTimeSlot>) {
        slots = nextSlots
        store.gateTimeSlots = nextSlots
        onScheduleChanged()
    }

    fun addPresetSlot(label: String, kind: GateSlotKind, startMinute: Int, endMinute: Int, days: Set<Int>) {
        val created = GateTimeSlot(
            id = "s_${System.currentTimeMillis()}_${slots.size}",
            label = label,
            kind = kind,
            startMinute = startMinute,
            endMinute = endMinute,
            daysOfWeek = days,
            enabled = true,
        )
        store.gateScheduleEnabled = true
        scheduleEnabled = true
        persistSlots(slots + created)
    }

    deleteCandidate?.let { slot ->
        ConfirmDeleteDialog(
            title = "Zeitfenster löschen?",
            message = "„${slot.displayLabel()}“ (${slot.formatTimeRange()}) wird entfernt.",
            onConfirm = {
                persistSlots(slots.filterNot { it.id == slot.id })
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }

    SubScreenTopBar(title = "Zeitplan & Pause", onBack = onBack)

    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(highlighted = true) {
                val statusTint = if (scheduleStatus.enforced) Good else AppColors.AccentGold
                val statusIcon = if (scheduleStatus.enforced) AppIcons.Lock else AppIcons.Clock
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(scheduleHeadline(scheduleStatus, nowMs), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = statusTint)
                        Text(scheduleSubline(scheduleStatus, nowMs), fontSize = 12.sp, color = AppColors.TextSecondary)
                    }
                }
            }
        }

        item {
            QuickPauseCard(
                store = store,
                scheduleStatus = scheduleStatus,
                nowMs = nowMs,
                showExtendWhenPaused = true,
                onScheduleChanged = onScheduleChanged,
            )
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Wochen-Zeitplan (Timeslots)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (slots.isNotEmpty()) {
                        Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = { checked ->
                                scheduleEnabled = checked
                                store.gateScheduleEnabled = checked
                                onScheduleChanged()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppColors.OnAccent,
                                checkedTrackColor = AppColors.AccentGold,
                            ),
                        )
                    }
                }
                val hasActiveFree = scheduleEnabled && slots.any { it.enabled && it.kind == GateSlotKind.FREE }
                val hasActiveBlock = scheduleEnabled && slots.any { it.enabled && it.kind == GateSlotKind.BLOCK }
                val explanation = when {
                    slots.isEmpty() ->
                        "Lege wiederkehrende Zeiten fest: z. B. „Ohne Sperre“ während der Arbeitszeit oder „Gesperrt“-Timeslots für Feierabend & Wochenende."
                    !scheduleEnabled ->
                        "Zeitplan ist pausiert – gesperrte Apps werden rund um die Uhr abgefangen."
                    hasActiveFree && hasActiveBlock ->
                        "Gesperrt in „Gesperrt“-Zeiten – „Ohne Sperre“-Fenster haben dabei Vorrang (z. B. Arbeit)."
                    hasActiveFree ->
                        "Nur außerhalb deiner „Ohne Sperre“-Zeiten wird gesperrt."
                    hasActiveBlock ->
                        "Nur innerhalb deiner „Gesperrt“-Zeiten wird gesperrt – außerhalb öffnen Apps frei."
                    else ->
                        "Alle Zeitfenster sind einzeln ausgeschaltet – die App-Sperre greift aktuell immer."
                }
                Text(explanation, fontSize = 12.sp, color = AppColors.TextSecondary)

                Button(
                    onClick = onAddSlot,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Zeitfenster hinzufügen", fontWeight = FontWeight.SemiBold)
                }

                if (slots.isEmpty()) {
                    HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))
                    Text("Schnell-Vorlagen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { addPresetSlot("Arbeit", GateSlotKind.FREE, 8 * 60, 17 * 60, GateTimeSlot.WEEKDAYS) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Arbeit frei (Mo–Fr 08:00–17:00)", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { addPresetSlot("Feierabend", GateSlotKind.BLOCK, 17 * 60, 23 * 60, GateTimeSlot.WEEKDAYS) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Feierabend gesperrt (Mo–Fr 17:00–23:00)", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { addPresetSlot("Wochenende", GateSlotKind.BLOCK, 9 * 60, 23 * 60, GateTimeSlot.WEEKEND) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Wochenende gesperrt (Sa–So 09:00–23:00)", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        items(slots, key = { it.id }) { slot ->
            val isMatchingNow = scheduleEnabled && slot.enabled && scheduleStatus.matchingSlot?.id == slot.id
            val kindBg = if (slot.kind == GateSlotKind.BLOCK) AppColors.GoodSoft else AppColors.AccentGoldSoft
            val kindFg = if (slot.kind == GateSlotKind.BLOCK) Good else AppColors.AccentGold
            SectionCard(highlighted = isMatchingNow, padding = PaddingValues(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onEditSlot(slot.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(7.dp), color = kindBg) {
                                Text(
                                    slot.kind.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = kindFg,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                            if (isMatchingNow) {
                                Text("· Jetzt aktiv", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = kindFg)
                            } else if (!scheduleEnabled || !slot.enabled) {
                                Text("· Aus", fontSize = 12.sp, color = AppColors.TextSecondary)
                            }
                        }
                        Text(
                            slot.formatTimeRange() + if (slot.wrapsOvernight) " (+1 Tag)" else "",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (slot.enabled) AppColors.TextPrimary else AppColors.TextSecondary,
                        )
                        Text(
                            "${slot.displayLabel()} · ${GateTimeSlot.formatDays(slot.daysOfWeek)}",
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = slot.enabled,
                        onCheckedChange = { checked ->
                            persistSlots(slots.map { if (it.id == slot.id) it.copy(enabled = checked) else it })
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppColors.OnAccent,
                            checkedTrackColor = AppColors.AccentGold,
                        ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onEditSlot(slot.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(AppIcons.Edit, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bearbeiten", fontSize = 12.sp, color = AppColors.AccentGold)
                    }
                    IconButton(onClick = { deleteCandidate = slot }, modifier = Modifier.size(36.dp)) {
                        Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Creates or edits a single [GateTimeSlot] with 24h time pickers and weekday selection. */
@Composable
private fun ColumnScope.GateSlotEditorScreen(
    store: Store,
    slotId: String?,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val existingSlots = remember(slotId) { store.gateTimeSlots }
    val existing = remember(slotId, existingSlots) {
        slotId?.let { id -> existingSlots.firstOrNull { it.id == id } }
    }

    var kind by remember(slotId) { mutableStateOf(existing?.kind ?: GateSlotKind.FREE) }
    var label by remember(slotId) { mutableStateOf(existing?.label ?: "") }
    var startMinute by remember(slotId) { mutableIntStateOf(existing?.startMinute ?: 8 * 60) }
    var endMinute by remember(slotId) { mutableIntStateOf(existing?.endMinute ?: 17 * 60) }
    var days by remember(slotId) { mutableStateOf(existing?.daysOfWeek ?: GateTimeSlot.WEEKDAYS) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog && existing != null) {
        ConfirmDeleteDialog(
            title = "Zeitfenster löschen?",
            message = "„${existing.displayLabel()}“ (${existing.formatTimeRange()}) wird entfernt.",
            onConfirm = {
                store.gateTimeSlots = existingSlots.filterNot { it.id == existing.id }
                showDeleteDialog = false
                onDeleted()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    SubScreenTopBar(
        title = if (existing == null) "Neues Zeitfenster" else "Zeitfenster bearbeiten",
        onBack = onCancel,
    )

    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Text("Art des Zeitfensters", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GateSlotKind.entries.forEach { option ->
                    ChipButton(
                        label = option.label,
                        selected = option == kind,
                        onClick = { kind = option },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                when (kind) {
                    GateSlotKind.BLOCK ->
                        "Während dieser Zeit greift die App-Sperre (mit deinen Aufgaben, Akkorden & Weiterleitungen)."
                    GateSlotKind.FREE ->
                        "Während dieser Zeit öffnen gesperrte Apps direkt ohne Sperre (z. B. auf der Arbeit) – hat Vorrang vor Sperr-Zeiten."
                },
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
        }

        SectionCard {
            Text("Name (optional)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text("z. B. Arbeit, Feierabend, Abends…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Text("Uhrzeit", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeFieldButton(
                    caption = "Von",
                    minute = startMinute,
                    onClick = { showTimePicker(context, startMinute) { startMinute = it } },
                    modifier = Modifier.weight(1f),
                )
                TimeFieldButton(
                    caption = "Bis",
                    minute = endMinute,
                    onClick = { showTimePicker(context, endMinute) { endMinute = it } },
                    modifier = Modifier.weight(1f),
                )
            }
            val timeNote = when {
                startMinute == endMinute ->
                    "Start- und Endzeit gleich = Ganztägig an den gewählten Tagen."
                startMinute > endMinute && endMinute == 0 ->
                    "Endet um Mitternacht (${GateTimeSlot.formatMinute(startMinute)} – 24:00 Uhr)."
                startMinute > endMinute ->
                    "Läuft über Mitternacht bis ${GateTimeSlot.formatMinute(endMinute)} am Folgetag."
                else -> null
            }
            if (timeNote != null) {
                Text(timeNote, fontSize = 12.sp, color = AppColors.AccentGold)
            }

            val presets = listOf(
                Triple("08:00–17:00", 8 * 60, 17 * 60),
                Triple("17:00–23:00", 17 * 60, 23 * 60),
                Triple("22:00–07:00", 22 * 60, 7 * 60),
                Triple("Ganztägig", 0, 0),
            )
            presets.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (title, s, e) ->
                        ChipButton(
                            label = title,
                            selected = startMinute == s && endMinute == e,
                            onClick = {
                                startMinute = s
                                endMinute = e
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        SectionCard {
            Text("Wochentage", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton(
                    label = "Täglich",
                    selected = days == GateTimeSlot.ALL_DAYS,
                    onClick = { days = GateTimeSlot.ALL_DAYS },
                    modifier = Modifier.weight(1f),
                )
                ChipButton(
                    label = "Mo–Fr",
                    selected = days == GateTimeSlot.WEEKDAYS,
                    onClick = { days = GateTimeSlot.WEEKDAYS },
                    modifier = Modifier.weight(1f),
                )
                ChipButton(
                    label = "Sa–So",
                    selected = days == GateTimeSlot.WEEKEND,
                    onClick = { days = GateTimeSlot.WEEKEND },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { day ->
                    val selected = day in days
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { days = if (selected) days - day else days + day },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) AppColors.AccentGold else AppColors.SurfaceCardAlt,
                        border = BorderStroke(1.dp, if (selected) AppColors.AccentGold else AppColors.BorderSubtle),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = GateTimeSlot.dayShortLabel(day),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AppColors.OnAccent else AppColors.TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            if (days.isEmpty()) {
                Text("Bitte mindestens einen Wochentag wählen.", fontSize = 12.sp, color = Bad)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = {
            val saved = GateTimeSlot(
                id = existing?.id ?: "s_${System.currentTimeMillis()}_${existingSlots.size}",
                label = label.trim(),
                kind = kind,
                startMinute = startMinute,
                endMinute = endMinute,
                daysOfWeek = days,
                enabled = existing?.enabled ?: true,
            )
            store.gateScheduleEnabled = true
            store.gateTimeSlots = if (existing == null) {
                existingSlots + saved
            } else {
                existingSlots.map { if (it.id == existing.id) saved else it }
            }
            onSaved()
        },
        enabled = days.isNotEmpty(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Speichern", fontWeight = FontWeight.SemiBold)
    }
    if (existing != null) {
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { showDeleteDialog = true },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Bad.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Zeitfenster löschen", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TimeFieldButton(
    caption: String,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.SurfaceCardAlt,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(caption, fontSize = 12.sp, color = AppColors.TextSecondary)
            Text(
                GateTimeSlot.formatMinute(minute),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.AccentGold,
            )
        }
    }
}
