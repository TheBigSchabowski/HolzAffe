package com.example.holzaffe

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SizeF
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Sends an explicit APPWIDGET_UPDATE broadcast to the bound widget's provider so it renders
 * fresh RemoteViews immediately after binding, configuration, or gate appearance.
 */
internal fun requestWidgetUpdate(context: Context, widgetId: Int) {
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
    val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return
    val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        .setComponent(info.provider)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
    runCatching { context.sendBroadcast(updateIntent) }
}

/**
 * Shown in front of a blocked app: switches between the active entries in the user's gate pool
 * (ear-training quiz, timed text tasks, app redirects, or live configured AppWidgets of 1..3 min)
 * and vibrates briefly on unlock.
 */
class GateActivity : ComponentActivity() {
    private var target by mutableStateOf("")
    private var solved by mutableIntStateOf(0)
    // Incremented on each new gate launch so a fresh challenge/deck is picked per app start.
    private var sessionId by mutableIntStateOf(0)

    // When a live Widget task is displayed and the user taps it (leaving GateActivity into the widget's app),
    // hand off the remaining countdown to GateService's floating overlay pill.
    private var pendingWidgetHandoff: Pair<GateTask, () -> Long>? = null
    private lateinit var widgetHost: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        runCatching { widgetHost.startListening() }
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        activeTargetPkg = target
        val store = Store(this)
        store.applySoundSettings()
        val required = store.required
        val mode = store.gateMode
        val poolSize = store.gatePoolSize
        val keyMode = store.gateKeyMode

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    val challenge = remember(target, sessionId) {
                        val picked = GateChallengePicker.pickNext(
                            includeQuiz = store.gateIncludeQuiz,
                            activeTasks = store.activeGateTasks(),
                            lastId = store.lastGateChallengeId,
                        )
                        store.lastGateChallengeId = picked.id
                        picked
                    }

                    // Keep the screen awake during a timed practice task so the countdown stays visible.
                    DisposableEffect(challenge) {
                        if (challenge is GateChallenge.TimedTask) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        onDispose {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            pendingWidgetHandoff = null
                        }
                    }

                    BackHandler { goHome() }

                    when (challenge) {
                        is GateChallenge.Quiz -> {
                            var paused by remember(target, sessionId) { mutableStateOf(false) }
                            val library = remember(target, sessionId) { Library(store) }
                            val gateTarget = remember(target, sessionId, mode) {
                                library.resolveSelection(store.gateSelection, mode)
                            }
                            // Apply optional per-run BPM/beat tempo for a single custom run, then restore global settings.
                            DisposableEffect(target, sessionId) {
                                val customTempo = (gateTarget as? RunTarget.Custom)?.run?.customTempo
                                if (customTempo != null) Synth.chordMs = customTempo.chordMs else store.applySoundSettings()
                                onDispose { store.applySoundSettings() }
                            }
                            val quiz = remember(target, sessionId, mode, poolSize, keyMode) {
                                // Read fresh per opening: the singleTask gate is reused via onNewIntent.
                                buildGateQuiz(
                                    targetSelection = gateTarget,
                                    library = library,
                                    store = store,
                                    poolSize = poolSize,
                                    keyMode = keyMode,
                                )
                            }
                            val nextGateQuestion = remember(quiz) { { quiz.next() } }
                            Column(
                                Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Erst hören, dann ${appLabel(target)}", fontSize = 22.sp)
                                Text(
                                    "${quiz.poolSize} Aufgaben · ${quiz.subtitle}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("$solved / $required", fontSize = 56.sp, color = MaterialTheme.colorScheme.primary)
                                LinearProgressIndicator(
                                    progress = { solved.toFloat() / required },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.weight(1f))
                                QuizPanel(
                                    active = solved < required,
                                    scores = store,
                                    mode = quiz.mode,
                                    paused = paused,
                                    onPausedChange = { paused = it },
                                    isTimed = false,
                                    nextQuestion = nextGateQuestion,
                                    onAnswer = { ok ->
                                        quiz.record(ok)
                                        if (ok && ++solved >= required) {
                                            unlockAndOpen(store, target)
                                        }
                                    },
                                )
                                TextButton(onClick = ::goHome) { Text("Doch nicht öffnen") }
                            }
                        }

                        is GateChallenge.TimedTask -> {
                            when (challenge.task.kind) {
                                GateTaskKind.APP -> {
                                    // Immediately launch the practice app with a floating countdown pill in GateService.
                                    LaunchedEffect(challenge.task.id, target) {
                                        val durationMs = challenge.task.minutes * 60_000L
                                        val launchIntent = packageManager.getLaunchIntentForPackage(challenge.task.targetPackage)
                                        if (launchIntent != null && GateService.startRedirect(target, challenge.task.targetPackage, durationMs)) {
                                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(launchIntent)
                                            finish()
                                        }
                                    }
                                    TimedTaskGate(
                                        appName = appLabel(target),
                                        task = challenge.task,
                                        onComplete = { unlockAndOpen(store, target) },
                                        onCancel = ::goHome,
                                    )
                                }

                                GateTaskKind.WIDGET -> {
                                    WidgetTaskGate(
                                        appName = appLabel(target),
                                        task = challenge.task,
                                        widgetHost = widgetHost,
                                        onRegisterHandoff = { remainingProvider ->
                                            pendingWidgetHandoff = challenge.task to remainingProvider
                                        },
                                        onFallbackOpenApp = { remainingMs ->
                                            pendingWidgetHandoff = null
                                            val pkg = challenge.task.targetPackage
                                            val launchIntent = if (pkg.isNotEmpty()) packageManager.getLaunchIntentForPackage(pkg) else null
                                            if (launchIntent != null && GateService.startRedirect(target, pkg, remainingMs)) {
                                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                startActivity(launchIntent)
                                                finish()
                                            }
                                        },
                                        onComplete = {
                                            pendingWidgetHandoff = null
                                            unlockAndOpen(store, target)
                                        },
                                        onCancel = {
                                            pendingWidgetHandoff = null
                                            goHome()
                                        },
                                    )
                                }

                                GateTaskKind.TEXT -> {
                                    TimedTaskGate(
                                        appName = appLabel(target),
                                        task = challenge.task,
                                        onComplete = { unlockAndOpen(store, target) },
                                        onCancel = ::goHome,
                                    )
                                }
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

    override fun onResume() {
        super.onResume()
        activeTargetPkg = target
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If the user tapped the embedded widget (opening e.g. Widder), hand the remaining timer to GateService.
        performWidgetHandoffIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isNotEmpty()) {
            target = pkg
            activeTargetPkg = pkg
            solved = 0
            sessionId++
        }
    }

    override fun onStop() {
        super.onStop()
        if (activeTargetPkg == target) activeTargetPkg = null
        // RemoteViews PendingIntents sent via the system server do not always invoke onUserLeaveHint(),
        // so also perform the handoff here when leaving interactively without finishing or rotating.
        val interactive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        if (!isChangingConfigurations && !isFinishing && interactive) {
            performWidgetHandoffIfNeeded()
        } else {
            pendingWidgetHandoff = null
        }
        runCatching { widgetHost.stopListening() }
        ChordPlayer.stop()
        // Leaving mid-challenge forfeits the progress (unless a widget handoff already passed the timer to GateService).
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (activeTargetPkg == target) activeTargetPkg = null
        super.onDestroy()
    }

    private fun performWidgetHandoffIfNeeded() {
        val handoff = pendingWidgetHandoff ?: return
        val (task, remainingProvider) = handoff
        val remainingMs = remainingProvider()
        pendingWidgetHandoff = null
        if (remainingMs > 0L) {
            GateService.startRedirect(target, task.targetPackage, remainingMs)
        }
    }

    private fun unlockAndOpen(store: Store, pkg: String) {
        vibrateShort()
        store.unlock(pkg)
        open(pkg)
    }

    /** Brief haptic pulse signaling that the gate finished and the target app is now open. */
    private fun vibrateShort() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun open(pkg: String) {
        if (activeTargetPkg == target) activeTargetPkg = null
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        finish()
    }

    /** Back must not fall through to the blocked app underneath. */
    private fun goHome() {
        pendingWidgetHandoff = null
        if (activeTargetPkg == target) activeTargetPkg = null
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val WIDGET_HOST_ID = 0x484F4C5A

        @Volatile
        var activeTargetPkg: String? = null
            private set
    }
}

/**
 * Displays the user's configured AppWidget live inside the gate screen.
 * Tapping the widget opens the widget's configured section (e.g. a specific Widder mode)
 * and hands off the remaining 1..3 min countdown to [GateService]'s floating pill.
 */
@Composable
private fun WidgetTaskGate(
    appName: String,
    task: GateTask,
    widgetHost: AppWidgetHost,
    onRegisterHandoff: (() -> Long) -> Unit,
    onFallbackOpenApp: (Long) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val widgetInfo = remember(task.widgetId) {
        if (task.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            AppWidgetManager.getInstance(context).getAppWidgetInfo(task.widgetId)
        } else {
            null
        }
    }
    val totalMs = task.minutes * 60_000L
    var remainingMs by remember(task.id) { mutableLongStateOf(totalMs) }

    LaunchedEffect(task.id) {
        onRegisterHandoff { remainingMs }
        var lastMark = TimeSource.Monotonic.markNow()
        while (remainingMs > 0L) {
            delay(100)
            val now = TimeSource.Monotonic.markNow()
            remainingMs = (remainingMs - (now - lastMark).inWholeMilliseconds).coerceAtLeast(0L)
            lastMark = now
        }
        onComplete()
    }

    val secsLeft = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    val progress = 1f - (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

    Column(
        Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Erst üben, dann $appName", fontSize = 22.sp, textAlign = TextAlign.Center)
        Text(
            "${secsLeft / 60}:${(secsLeft % 60).toString().padStart(2, '0')}",
            fontSize = 60.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(
            "Tippe auf dein Widget (${task.text}) – der Timer läuft dort weiter und öffnet danach $appName.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
        )
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 340.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (widgetInfo != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 316.dp).padding(12.dp),
                    factory = { ctx ->
                        val container = FrameLayout(ctx)
                        requestWidgetUpdate(ctx, task.widgetId)
                        // Use Activity context (ctx) so widget PendingIntents & theme attributes work cleanly.
                        val hostView = widgetHost.createView(ctx, task.widgetId, widgetInfo)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(320f, 220f)))
                        } else {
                            @Suppress("DEPRECATION")
                            hostView.updateAppWidgetSize(Bundle(), 320, 180, 380, 300)
                        }
                        container.addView(
                            hostView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        container
                    },
                )
            } else {
                Text(
                    "Widget nicht mehr verfügbar – bitte unter Sperr-Aufgaben neu verknüpfen.",
                    fontSize = 14.sp,
                    color = Bad,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        }
        if (task.targetPackage.isNotEmpty()) {
            Button(
                onClick = { onFallbackOpenApp(remainingMs) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("${task.text} direkt öffnen")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Doch nicht öffnen") }
    }
}

/** Full-screen countdown lock showing the user's custom practice task for 1..3 minutes. */
@Composable
private fun TimedTaskGate(
    appName: String,
    task: GateTask,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    val totalMs = task.minutes * 60_000L
    var remainingMs by remember(task.id) { mutableLongStateOf(totalMs) }

    LaunchedEffect(task.id) {
        var lastMark = TimeSource.Monotonic.markNow()
        while (remainingMs > 0L) {
            delay(100)
            val now = TimeSource.Monotonic.markNow()
            remainingMs = (remainingMs - (now - lastMark).inWholeMilliseconds).coerceAtLeast(0L)
            lastMark = now
        }
        onComplete()
    }

    val secsLeft = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    val progress = 1f - (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

    Column(
        Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Erst Aufgabe, dann $appName", fontSize = 22.sp, textAlign = TextAlign.Center)
        Text(
            "${secsLeft / 60}:${(secsLeft % 60).toString().padStart(2, '0')}",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Deine Aufgabe (${task.minutes} min)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    task.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Doch nicht öffnen") }
    }
}

/** Question source of one gate opening: a built-in [GateSessionDeck] or an own/combined [CustomRunDeck]. */
private class GateQuiz(
    val poolSize: Int,
    val subtitle: String,
    val mode: GameMode,
    val next: () -> Question,
    val record: (Boolean) -> Unit,
)

private fun keyModeSubtitle(keyMode: GateKeyMode, minor: Boolean?): String = when (keyMode) {
    GateKeyMode.SINGLE_KEY_TONALITY -> when (minor) {
        true -> "1 Tonart (Moll)"
        false -> "1 Tonart (Dur)"
        null -> "1 Tonart"
    }
    GateKeyMode.SAME_ROOT -> "1 Grundton (Dur + Moll)"
    GateKeyMode.RANDOM -> "alle Tonarten"
}

private fun pickSessionTonality(items: List<RunItem>): Boolean? {
    val tonalities = items.mapNotNullTo(HashSet()) { item ->
        when (val ex = (item as? RunItem.Builtin)?.exercise) {
            is Progression -> ex.minor
            is Cadence -> ex.minor
            else -> null
        }
    }
    return when (tonalities.size) {
        0 -> null
        1 -> tonalities.first()
        else -> Random.nextBoolean()
    }
}

private fun buildGateQuiz(
    targetSelection: RunTarget,
    library: Library,
    store: Store,
    poolSize: Int,
    keyMode: GateKeyMode,
): GateQuiz = when (targetSelection) {
    is RunTarget.Builtin -> {
        val deck = GateSessionDeck(
            mode = targetSelection.mode,
            maxPoolSize = poolSize,
            keyMode = keyMode,
            disabledIds = store.gateDisabledExerciseIds,
        )
        GateQuiz(
            poolSize = deck.pool.size,
            subtitle = keyModeSubtitle(keyMode, deck.sessionMinor),
            mode = targetSelection.mode,
            next = deck::nextQuestion,
            record = {},
        )
    }

    is RunTarget.Custom -> {
        val fixedKey = if (keyMode != GateKeyMode.RANDOM) 48 + Random.nextInt(12) else null
        val singleTonality = if (keyMode == GateKeyMode.SINGLE_KEY_TONALITY) pickSessionTonality(targetSelection.items) else null
        val deck = CustomRunDeck(
            targetSelection.items,
            allAudios = library.audios,
            maxPoolSize = poolSize,
            fixedKey = fixedKey,
            singleTonality = singleTonality,
            allCustomChords = library.customChords,
        )
        GateQuiz(
            poolSize = deck.pool.size,
            subtitle = "Run „${targetSelection.run.name}“ · ${keyModeSubtitle(keyMode, singleTonality)}",
            mode = GameMode.MIX,
            next = deck::nextQuestion,
            record = deck::record,
        )
    }

    is RunTarget.Combined -> {
        if (targetSelection.runs.isEmpty()) {
            val deck = GateSessionDeck(
                mode = GameMode.MIX,
                maxPoolSize = poolSize,
                keyMode = keyMode,
                disabledIds = store.gateDisabledExerciseIds,
                modes = targetSelection.modes.toSet(),
            )
            GateQuiz(
                poolSize = deck.pool.size,
                subtitle = "${targetSelection.label} · ${keyModeSubtitle(keyMode, deck.sessionMinor)}",
                mode = GameMode.MIX,
                next = deck::nextQuestion,
                record = {},
            )
        } else {
            val disabled = store.gateDisabledExerciseIds
            val modeItems = targetSelection.modes.flatMap { m ->
                val allMode = EXERCISES_BY_MODE.getValue(m)
                allMode.filter { it.id !in disabled }.ifEmpty { allMode }.map { RunItem.Builtin(it) }
            }
            val combinedItems = (modeItems + targetSelection.runs.flatMap { library.items(it) }).distinctBy { it.id }
            val fixedKey = if (keyMode != GateKeyMode.RANDOM) 48 + Random.nextInt(12) else null
            val singleTonality = if (keyMode == GateKeyMode.SINGLE_KEY_TONALITY) pickSessionTonality(combinedItems) else null
            val deck = CustomRunDeck(
                combinedItems,
                allAudios = library.audios,
                maxPoolSize = poolSize,
                fixedKey = fixedKey,
                singleTonality = singleTonality,
                allCustomChords = library.customChords,
            )
            GateQuiz(
                poolSize = deck.pool.size,
                subtitle = "${targetSelection.label} · ${keyModeSubtitle(keyMode, singleTonality)}",
                mode = GameMode.MIX,
                next = deck::nextQuestion,
                record = deck::record,
            )
        }
    }
}
