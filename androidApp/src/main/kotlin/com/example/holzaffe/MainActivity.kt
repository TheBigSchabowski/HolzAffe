package com.example.holzaffe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** The app-gate block at the bottom of the home screen. */
@Composable
private fun ColumnScope.GateSection(store: Store, onApps: () -> Unit) {
    val context = LocalContext.current
    var gateOn by remember { mutableStateOf(gateEnabled(context)) }
    var required by remember { mutableIntStateOf(store.required) }
    var minutes by remember { mutableIntStateOf(store.unlockMinutes) }
    LifecycleResumeEffect(Unit) {
        gateOn = gateEnabled(context)
        onPauseOrDispose {}
    }

    Spacer(Modifier.weight(1f))
    Text("App-Sperre", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    if (gateOn) {
        Text("Aktiv · ${store.blocked.size} App(s) gesperrt", color = Good)
    } else {
        Text("Aus – Bedienungshilfe muss erlaubt werden", color = Bad)
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("Bedienungshilfe öffnen")
        }
    }
    OutlinedButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) { Text("Gesperrte Apps wählen") }
    Stepper("Richtige zum Öffnen", required, 1..20) { required = it; store.required = it }
    Stepper("Minuten offen danach", minutes, 1..60) { minutes = it; store.unlockMinutes = it }
}

private class AppEntry(val pkg: String, val label: String)

@Composable
private fun ColumnScope.Apps(store: Store, onExit: () -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blocked) }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedWith(compareBy({ it.pkg !in store.blocked }, { it.label.lowercase() }))
    }

    Text("Gesperrte Apps", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    LazyColumn(Modifier.weight(1f)) {
        items(apps, key = { it.pkg }) { app ->
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
