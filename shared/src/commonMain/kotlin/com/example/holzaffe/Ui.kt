package com.example.holzaffe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** Compact two-row selector for the five ear-training modes. */
@Composable
fun ModePicker(selected: GameMode, onSelect: (GameMode) -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(
        listOf(GameMode.DEGREES to "Stufen", GameMode.CHORD_TYPES to "Typen", GameMode.CADENCES to "Kadenzen"),
        listOf(GameMode.INVERSIONS to "Umkehrungen", GameMode.MIX to "Mix (Alle)"),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (mode, title) ->
                    val isSelected = mode == selected
                    Button(
                        onClick = { onSelect(mode) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                        ),
                        modifier = Modifier.weight(1f).height(38.dp),
                    ) {
                        Text(
                            title,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Plays one exercise after the other and reports each answer. Shared by the
 * timed run and the Android app gate; [active] false freezes it.
 */
@Composable
fun QuizPanel(
    active: Boolean,
    onAnswer: (correct: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    scores: Scores? = null,
    mode: GameMode = GameMode.DEGREES,
) {
    val review = remember(scores) { ReviewQueue.decode(scores?.reviewQueue.orEmpty()) }
    var question by remember(mode) { mutableStateOf(review.nextQuestion(mode)) }
    var picked by remember(mode) { mutableStateOf<Int?>(null) }

    LaunchedEffect(question) {
        withContext(Dispatchers.Default) { ChordPlayer.play(question.chords) }
    }
    LaunchedEffect(picked, active) {
        val p = picked ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        // A miss stays on screen a little longer so the right answer can sink in.
        delay(if (p == question.correct) 250 else 1100)
        picked = null
        question = review.nextQuestion(mode)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            question.prompt,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { ChordPlayer.play(question.chords) },
                enabled = active,
                modifier = Modifier.weight(1f),
            ) { Text("▶  Nochmal", maxLines = 1) }
            OutlinedButton(
                onClick = { ChordPlayer.playArpeggiated(question.chords) },
                enabled = active,
                modifier = Modifier.weight(1f),
            ) { Text("♪  Zerlegt", maxLines = 1) }
        }

        question.choices.indices.chunked(3).forEach { rowIndices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowIndices.forEach { i ->
                    val label = question.choices[i]
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
                                val ok = i == question.correct
                                review.record(question.progressionId, ok, mode)
                                scores?.let { it.reviewQueue = review.encode() }
                                onAnswer(ok)
                            }
                        },
                        enabled = active,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(70.dp),
                    ) {
                        ChoiceLabel(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceLabel(label: String) {
    when {
        " → " in label -> {
            val parts = label.split(" → ", limit = 2)
            val target = parts[1]
            val targetSize = when {
                target.length <= 4 -> 22.sp
                target.length <= 7 -> 17.sp
                else -> 14.sp
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${parts[0]} →", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                Text(target, fontSize = targetSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        " · " in label -> {
            val parts = label.split(" · ", limit = 2)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(parts[0], fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(parts[1], fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
        else -> Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
    var mode by remember { mutableStateOf(scores.gameMode) }
    val pending = ReviewQueue.decode(scores.reviewQueue).pendingCount(mode)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HolzAffe", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("1:30 · Gehörbildung im Puzzle-Rush")
        ModePicker(selected = mode, onSelect = { mode = it; scores.gameMode = it })
        Spacer(Modifier.height(4.dp))
        Text("All-time High · ${mode.label}", color = MaterialTheme.colorScheme.primary)
        Text("${scores.highScore(mode)}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Text("Aktuelle Serie ${scores.streak} · längste Serie ${scores.bestStreak}")
        if (pending > 0) {
            Text(
                "$pending offene Wiederholung(en)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(58.dp)) {
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
    val mode = remember(round) { scores.gameMode }
    var left by remember(round) { mutableIntStateOf(RUN_SECONDS) }
    var score by remember(round) { mutableIntStateOf(0) }
    // The streak carries over from earlier runs; only a miss resets it.
    var streak by remember(round) { mutableIntStateOf(scores.streak) }
    var bestStreak by remember(round) { mutableIntStateOf(streak) }
    var errors by remember(round) { mutableIntStateOf(0) }
    val previousBest = remember(round) { scores.highScore(mode) }
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
        Text("$score", fontSize = 84.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("${mode.label} · Bestwert $previousBest · Serie-Rekord $previousStreak")
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
            // Keyed by round so a restart begins with a fresh exercise.
            key(round) {
                QuizPanel(active = true, scores = scores, mode = mode, onAnswer = { ok ->
                    if (ok) {
                        score++
                        streak++
                        if (streak > bestStreak) bestStreak = streak
                        // Saved per answer so leaving mid-run keeps a record.
                        if (score > scores.highScore(mode)) scores.setHighScore(mode, score)
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
