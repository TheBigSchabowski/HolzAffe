package com.example.holzaffe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val RUN_SECONDS = 90

val Good = Color(0xFF3FB950)
val Bad = Color(0xFFE5534B)

private val Colors = darkColorScheme(
    primary = Color(0xFFE4A72E),
    onPrimary = Color(0xFF1B1F3B),
    background = Color(0xFF14172B),
    surface = Color(0xFF14172B),
    surfaceVariant = Color(0xFF252A4A),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

/**
 * Plays one progression after the other and reports each answer. Shared by the
 * timed run and the Android app gate; [active] false freezes it.
 */
@Composable
fun QuizPanel(active: Boolean, onAnswer: (correct: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var question by remember { mutableStateOf(QuestionFactory.next()) }
    var picked by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(question) {
        withContext(Dispatchers.Default) { ChordPlayer.play(question.chords) }
    }
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        // A miss stays on screen a little longer so the right answer can sink in.
        delay(if (p == question.correct) 250 else 900)
        picked = null
        question = QuestionFactory.next()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { ChordPlayer.play(question.chords) },
            enabled = active,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("▶  Nochmal hören") }

        question.choices.forEachIndexed { i, label ->
            val color = when {
                picked == null -> MaterialTheme.colorScheme.surfaceVariant
                i == question.correct -> Good
                i == picked -> Bad
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Button(
                onClick = {
                    if (picked == null) {
                        picked = i
                        onAnswer(i == question.correct)
                    }
                },
                enabled = active,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(84.dp),
            ) { Text(label, fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The whole game: home screen and timed run. [extra] is the platform's own section
 * under the game settings (the app gate on Android); [onRunning] tells the host
 * whether a run is on screen, so it can route its back button to [RunControl.exit].
 */
@Composable
fun GameApp(
    scores: Scores,
    control: RunControl = remember { RunControl() },
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    if (control.running) {
        Run(scores, onExit = { control.running = false })
    } else {
        Home(scores, onRun = { control.running = true }, extra = extra)
    }
}

/** Lets the host see and end a run (Android back button). */
class RunControl {
    var running by mutableStateOf(false)
}

@Composable
private fun Home(scores: Scores, onRun: () -> Unit, extra: @Composable ColumnScope.() -> Unit) {
    var maxErrors by remember { mutableIntStateOf(scores.maxErrors) }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HolzAffe", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("1:30 · so viele Akkordfolgen wie möglich")
        Spacer(Modifier.height(12.dp))
        Text("All-time High", color = MaterialTheme.colorScheme.primary)
        Text("${scores.highScore}", fontSize = 84.sp, fontWeight = FontWeight.Bold)
        Text("Aktuelle Serie ${scores.streak} · längste Serie ${scores.bestStreak}")
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Run starten", fontSize = 22.sp)
        }
        Stepper(
            if (maxErrors == 0) "Abbruch nach Fehlern: aus" else "Abbruch nach Fehlern",
            maxErrors, 0..10,
        ) { maxErrors = it; scores.maxErrors = it }
        extra()
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }) { Text("−", fontSize = 22.sp) }
        Text("$value", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun Run(scores: Scores, onExit: () -> Unit) {
    var round by remember { mutableIntStateOf(0) } // bumping it restarts the run
    var left by remember(round) { mutableIntStateOf(RUN_SECONDS) }
    var score by remember(round) { mutableIntStateOf(0) }
    // The streak carries over from earlier runs; only a miss resets it.
    var streak by remember(round) { mutableIntStateOf(scores.streak) }
    var bestStreak by remember(round) { mutableIntStateOf(streak) }
    var errors by remember(round) { mutableIntStateOf(0) }
    val previousBest = remember(round) { scores.highScore }
    val previousStreak = remember(round) { scores.bestStreak }
    val maxErrors = remember(round) { scores.maxErrors } // 0 = errors never end the run
    val failed = maxErrors > 0 && errors >= maxErrors
    val over = left <= 0 || failed

    LaunchedEffect(round) {
        val start = TimeSource.Monotonic.markNow()
        while (left > 0 && !(maxErrors > 0 && errors >= maxErrors)) {
            delay(200)
            left = (RUN_SECONDS - start.elapsedNow().inWholeSeconds).toInt().coerceAtLeast(0)
        }
    }
    LaunchedEffect(over) {
        if (over) ChordPlayer.stop()
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (left <= 10) Bad else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text("Serie $streak", fontSize = 26.sp, color = if (streak >= 5) Good else MaterialTheme.colorScheme.onSurface)
                if (maxErrors > 0) Text("Fehler $errors / $maxErrors", color = Bad)
            }
        }
        Text("$score", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Bestwert $previousBest · längste Serie $previousStreak")
        Spacer(Modifier.weight(1f))

        if (over) {
            Text(
                when {
                    score > previousBest -> "Neues All-time High!"
                    failed -> "$maxErrors Fehler – Run vorbei"
                    else -> "Zeit um"
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (score > previousBest) Good else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Längste Serie: $bestStreak" + if (bestStreak > previousStreak) " – Rekord!" else "",
                color = if (bestStreak > previousStreak) Good else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { round++ }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Nochmal", fontSize = 22.sp)
            }
            TextButton(onClick = onExit) { Text("Zurück") }
        } else {
            // Keyed by round so a restart begins with a fresh progression.
            key(round) {
                QuizPanel(active = true, onAnswer = { ok ->
                    if (ok) {
                        score++
                        streak++
                        if (streak > bestStreak) bestStreak = streak
                        // Saved per answer so leaving mid-run keeps a record.
                        if (score > scores.highScore) scores.highScore = score
                        scores.streak = streak
                        if (streak > scores.bestStreak) scores.bestStreak = streak
                    } else {
                        streak = 0
                        scores.streak = 0
                        errors++
                    }
                })
            }
            TextButton(onClick = onExit) { Text("Abbrechen") }
        }
    }
}
