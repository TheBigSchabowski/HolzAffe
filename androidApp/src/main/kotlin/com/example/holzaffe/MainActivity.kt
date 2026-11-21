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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    var pickingApps by remember { mutableStateOf(false) }
                    val control = remember { RunControl() }
                    BackHandler(enabled = pickingApps || control.running) {
                        pickingApps = false
                        control.running = false
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize()) {
                        if (pickingApps) {
                            Apps(store, onExit = { pickingApps = false })
                        } else {
                            GameApp(store, control) { GateSection(store, onApps = { pickingApps = true }) }
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
private fun ColumnScope.GateSection(store: Store, onApps: () -> Unit) {
    val context = LocalContext.current
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var gateOn by remember { mutableStateOf(gateEnabled(context)) }
    var required by remember { mutableIntStateOf(store.required) }
    var gatePoolSize by remember { mutableIntStateOf(store.gatePoolSize) }
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
    Text("Modus der App-Sperre", fontSize = 14.sp)
    ModePicker(selected = gateMode, onSelect = { gateMode = it; store.gateMode = it })
    Stepper("Wechselnde Aufgaben pro Sperre", gatePoolSize, 2..18) { gatePoolSize = it; store.gatePoolSize = it }
    Stepper("Richtige zum Öffnen", required, 1..20) { required = it; store.required = it }
    Stepper("Minuten offen danach", minutes, 1..60) { minutes = it; store.unlockMinutes = it }
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

private fun gateEnabled(context: Context): Boolean {
    val me = ComponentName(context, GateService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled.orEmpty().split(':').any { it.equals(me, ignoreCase = true) }
}
