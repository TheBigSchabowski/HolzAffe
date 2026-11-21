package com.example.holzaffe

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

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
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val store = Store(this)
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
                            val deck = remember(target, sessionId, mode, poolSize, keyMode) {
                                GateSessionDeck(
                                    mode = mode,
                                    maxPoolSize = poolSize,
                                    keyMode = keyMode,
                                )
                            }
                            val nextGateQuestion = remember(deck) { { deck.nextQuestion() } }
                            val keySubtitle = when (keyMode) {
                                GateKeyMode.SINGLE_KEY_TONALITY ->
                                    if (deck.sessionMinor == true) "1 Tonart (Moll)" else "1 Tonart (Dur)"
                                GateKeyMode.SAME_ROOT -> "1 Grundton (Dur + Moll)"
                                GateKeyMode.RANDOM -> "alle Tonarten"
                            }
                            Column(
                                Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Erst hören, dann ${appLabel(target)}", fontSize = 22.sp)
                                Text(
                                    "${deck.pool.size} Aufgaben · $keySubtitle",
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
                                    mode = mode,
                                    paused = paused,
                                    onPausedChange = { paused = it },
                                    isTimed = false,
                                    nextQuestion = nextGateQuestion,
                                    onAnswer = { ok ->
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If the user tapped the embedded widget (opening e.g. Widder), hand the remaining timer to GateService.
        val handoff = pendingWidgetHandoff ?: return
        val (task, remainingProvider) = handoff
        val remainingMs = remainingProvider()
        if (remainingMs > 0L) {
            GateService.startRedirect(target, task.targetPackage, remainingMs)
        }
        pendingWidgetHandoff = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg != target) {
            target = pkg
            solved = 0
            sessionId++
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
        ChordPlayer.stop()
        // Leaving mid-challenge forfeits the progress (unless a widget handoff already passed the timer to GateService).
        if (!isChangingConfigurations) finish()
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
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        finish()
    }

    /** Back must not fall through to the blocked app underneath. */
    private fun goHome() {
        pendingWidgetHandoff = null
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                factory = { ctx ->
                    val container = FrameLayout(ctx)
                    val mgr = AppWidgetManager.getInstance(ctx)
                    val info = mgr.getAppWidgetInfo(task.widgetId)
                    if (info != null) {
                        val hostView = widgetHost.createView(ctx.applicationContext, task.widgetId, info)
                        hostView.setAppWidget(task.widgetId, info)
                        container.addView(
                            hostView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                    container
                },
            )
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
