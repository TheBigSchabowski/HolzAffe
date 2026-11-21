package com.example.holzaffe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

private enum class MainScreen { HOME, APPS, TASKS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(MainScreen.HOME) }
                    val control = remember { RunControl() }
                    BackHandler(enabled = screen != MainScreen.HOME || control.running) {
                        screen = MainScreen.HOME
                        control.running = false
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize()) {
                        when (screen) {
                            MainScreen.APPS -> Apps(store, onExit = { screen = MainScreen.HOME })
                            MainScreen.TASKS -> GateTasksScreen(store, onExit = { screen = MainScreen.HOME })
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

    override fun onStop() {
        super.onStop()
        ChordPlayer.stop()
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

@Composable
private fun ColumnScope.Apps(store: Store, onExit: () -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blocked) }
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedWith(compareBy({ it.pkg !in store.blocked }, { it.label.lowercase() }))
    }
    // Filter by app name or package name; prefix matches (e.g. "whats" -> "WhatsApp") rank first.
    val filtered = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            apps
        } else {
            apps.filter { it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle) }
                .sortedWith(
                    compareBy(
                        { !it.label.lowercase().startsWith(needle) },
                        { it.label.lowercase() },
                    )
                )
        }
    }

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
    LazyColumn(Modifier.weight(1f)) {
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
 * Submenu to create timed text tasks (1..3 min) and move them into or out of the active
 * app-gate rotation field. By default only the ear-training quiz is active; adding custom
 * tasks makes the gate alternate between all active entries on each app opening.
 */
@Composable
private fun ColumnScope.GateTasksScreen(store: Store, onExit: () -> Unit) {
    var tasks by remember { mutableStateOf(store.gateTasks) }
    var activeIds by remember { mutableStateOf(store.gateActiveTaskIds) }
    var includeQuiz by remember { mutableStateOf(store.gateIncludeQuiz) }
    var newText by remember { mutableStateOf("") }
    var newMinutes by remember { mutableIntStateOf(1) }

    val activeTasks = tasks.filter { it.id in activeIds }
    val inactiveTasks = tasks.filter { it.id !in activeIds }
    val totalActiveCount = (if (includeQuiz) 1 else 0) + activeTasks.size

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

    Text("Sperr-Aufgaben", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(
        "Ziehe eigene Aufgaben (1–3 Min.) ins aktive Feld – die App-Sperre wechselt dann bei jedem Öffnen dazwischen.",
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
                                TextButton(onClick = { persist(nextQuiz = false) }) { Text("Raus") }
                            }
                        }
                    }
                    activeTasks.forEach { task ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${task.minutes} Min. · vibriert beim Öffnen", fontSize = 12.sp, color = Good)
                            }
                            TextButton(onClick = { persist(nextActive = activeIds - task.id) }) { Text("Raus") }
                        }
                    }
                }
            }
        }

        // Create a new 1..3 min task.
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
                    Text("Neue Aufgabe erstellen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        placeholder = { Text("z. B. Akkorde übers Griffbrett") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                    Button(
                        onClick = {
                            val trimmed = newText.trim()
                            if (trimmed.isNotEmpty()) {
                                val created = GateTask(
                                    id = "t_${System.currentTimeMillis()}_${tasks.size}",
                                    text = trimmed,
                                    minutes = newMinutes,
                                )
                                persist(nextTasks = tasks + created)
                                newText = ""
                            }
                        },
                        enabled = newText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("+ Aufgabe speichern")
                    }
                }
            }
        }

        // Available items that can be moved into the active field.
        if (!includeQuiz || inactiveTasks.isNotEmpty()) {
            item {
                Text(
                    "Verfügbar (ins aktive Feld ziehen)",
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
                        Text(task.text, fontSize = 14.sp)
                        Text("${task.minutes} Min.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                    Button(onClick = { persist(nextActive = activeIds + task.id) }) { Text("+ Aktiv", fontSize = 13.sp) }
                    TextButton(onClick = { persist(nextTasks = tasks.filterNot { it.id == task.id }) }) {
                        Text("×", color = Bad, fontSize = 18.sp)
                    }
                }
            }
        }
    }
    Button(onClick = onExit, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Fertig") }
}

private fun gateEnabled(context: Context): Boolean {
    val me = ComponentName(context, GateService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled.orEmpty().split(':').any { it.equals(me, ignoreCase = true) }
}
