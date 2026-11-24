package com.example.holzaffe

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val RUN_SECONDS = 90

/** C-Dur → F-Dur → G-Dur → C-Dur: lets the user hear the chord-duration setting. */
private val SOUND_SAMPLE = listOf(listOf(48, 52, 55), listOf(48, 53, 57), listOf(47, 50, 55), listOf(48, 52, 55))

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

/** Rounded toggle button used by the mode, run, key and relock selectors. */
@Composable
fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        ),
        modifier = modifier.height(38.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact two-row selector for the five ear-training modes; [selected] null highlights none. */
@Composable
fun ModePicker(selected: GameMode?, onSelect: (GameMode) -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(
        listOf(GameMode.DEGREES to "Stufen", GameMode.CHORD_TYPES to "Typen", GameMode.CADENCES to "Kadenzen"),
        listOf(GameMode.INVERSIONS to "Umkehrungen", GameMode.MIX to "Mix (Alle)"),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (mode, title) ->
                    ChipButton(title, mode == selected, { onSelect(mode) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The five built-in modes plus the user's own runs. [selectedRunId] non-empty means one of
 * [runs] is active, otherwise [mode] is.
 */
@Composable
fun RunPicker(
    mode: GameMode,
    selectedRunId: String,
    runs: List<CustomRun>,
    onSelectMode: (GameMode) -> Unit,
    onSelectRun: (CustomRun) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeRun = runs.firstOrNull { it.id == selectedRunId }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePicker(selected = if (activeRun == null) mode else null, onSelect = onSelectMode)
        runs.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { run ->
                    ChipButton("★ ${run.name}", run.id == activeRun?.id, { onSelectRun(run) }, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A titled block of selectable items for [ItemChecklist]. */
data class ChecklistGroup(val title: String, val items: List<RunItem>)

/** Checklist groups for the built-in exercises of [mode] (all modes for [GameMode.MIX]). */
fun builtinChecklistGroups(mode: GameMode): List<ChecklistGroup> =
    exerciseGroups(mode).map { g -> ChecklistGroup(g.title, g.exercises.map { RunItem.Builtin(it) }) }

/**
 * Grouped checkboxes with "Alle"/"Keine" per group and a ▶ preview per item. Used by the
 * run editor and the Android app-gate exercise selection.
 */
@Composable
fun ItemChecklist(
    groups: List<ChecklistGroup>,
    isChecked: (String) -> Boolean,
    onChange: (ids: Set<String>, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        groups.filter { it.items.isNotEmpty() }.forEach { group ->
            val ids = group.items.map { it.id }.toSet()
            item(key = "header:${group.title}") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${group.title} · ${ids.count(isChecked)}/${ids.size}",
                        Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = { onChange(ids, true) }) { Text("Alle") }
                    TextButton(onClick = { onChange(ids, false) }) { Text("Keine") }
                }
            }
            items(group.items, key = { "${group.title}:${it.id}" }) { item ->
                val checked = isChecked(item.id)
                val toggle = { onChange(setOf(item.id), !checked) }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = toggle),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Text(item.label, Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { item.preview() }) { Text("▶", fontSize = 18.sp) }
                }
            }
        }
    }
}

/**
 * Plays one exercise after the other and reports each answer. Shared by the
 * timed run and the Android app gate; [active] false freezes it.
 *
 * When [nextQuestion] is supplied (e.g. a per-launch [GateSessionDeck]), questions are drawn
 * exclusively from that provider instead of the global [ReviewQueue].
 */
@Composable
fun QuizPanel(
    active: Boolean,
    onAnswer: (correct: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    scores: Scores? = null,
    mode: GameMode = GameMode.DEGREES,
    paused: Boolean = false,
    onPausedChange: (Boolean) -> Unit = {},
    isTimed: Boolean = false,
    nextQuestion: (() -> Question)? = null,
) {
    val review = remember(scores) { ReviewQueue.decode(scores?.reviewQueue.orEmpty()) }
    val drawQuestion = { nextQuestion?.invoke() ?: review.nextQuestion(mode) }
    val missDelayMs = (scores?.missDelaySec ?: DEFAULT_MISS_DELAY_SEC).coerceIn(1, 10) * 1000L
    var question by remember(mode, nextQuestion) { mutableStateOf(drawQuestion()) }
    var lastQuestion by remember(mode, nextQuestion) { mutableStateOf<Question?>(null) }
    var picked by remember(mode, nextQuestion) { mutableStateOf<Int?>(null) }
    var inspectPrevious by remember(paused) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { ChordPlayer.stop() }
    }
    LaunchedEffect(question) {
        withContext(Dispatchers.Default) { question.play() }
    }
    LaunchedEffect(picked, active, paused) {
        val p = picked ?: return@LaunchedEffect
        if (!active || paused) return@LaunchedEffect
        // A miss stays on screen for the user-configured duration (default 3s) so the right answer can sink in.
        delay(if (p == question.correct) 250L else missDelayMs)
        lastQuestion = question
        picked = null
        question = drawQuestion()
    }

    if (paused) {
        val canInspectPrevious = picked == null && lastQuestion != null
        val displayed = if (canInspectPrevious && inspectPrevious) lastQuestion!! else question
        CheckCard(
            question = displayed,
            isTimed = isTimed,
            canInspectPrevious = canInspectPrevious,
            inspectPrevious = inspectPrevious,
            onSelectPrevious = { inspectPrevious = it },
            onResume = {
                lastQuestion = question
                picked = null
                question = drawQuestion()
                onPausedChange(false)
            },
            modifier = modifier,
        )
        return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusText = if (picked != null) {
            val answer = question.choices[question.correct]
            if (question.chordSummary.isBlank()) answer else "$answer  ·  ${question.chordSummary}"
        } else {
            question.prompt
        }
        val statusColor = if (picked == question.correct) Good else MaterialTheme.colorScheme.primary
        Text(
            statusText,
            fontSize = 14.sp,
            fontWeight = if (picked != null) FontWeight.SemiBold else FontWeight.Normal,
            color = statusColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { question.play() },
                enabled = active,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("▶ Nochmal", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { ChordPlayer.playArpeggiated(question.chords) },
                // A recording cannot be rolled note by note.
                enabled = active && !question.isAudio,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("♪ Zerlegt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { onPausedChange(true) },
                enabled = active,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("⏸ Check", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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
                // Keep button width constant when a custom run has fewer than nine answers.
                repeat(3 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CheckCard(
    question: Question,
    isTimed: Boolean,
    canInspectPrevious: Boolean,
    inspectPrevious: Boolean,
    onSelectPrevious: (Boolean) -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (isTimed) "Check-Modus · Zeit pausiert" else "Check-Modus",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (canInspectPrevious) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "Aktuelle", true to "Vorherige").forEach { (prev, label) ->
                        val active = inspectPrevious == prev
                        Button(
                            onClick = { onSelectPrevious(prev) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
                            ),
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
            Text(
                question.choices[question.correct],
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Good,
            )
            if (question.isAudio) {
                Text("Eigenes Audio", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f))
            } else Text(
                question.chordSummary,
                fontSize = if (question.chordSummary.length > 20) 20.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                question.chordInfos.forEachIndexed { idx, info ->
                    OutlinedButton(
                        onClick = { ChordPlayer.play(listOf(question.chords[idx])) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "▶ ${info.name}",
                                fontSize = if (info.name.length > 7) 12.sp else 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                "${info.degree} · ${info.notes}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { question.play() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (question.isAudio) "▶ Abspielen" else "▶ Alle", maxLines = 1) }
                if (!question.isAudio) {
                    OutlinedButton(
                        onClick = { ChordPlayer.playArpeggiated(question.chords) },
                        modifier = Modifier.weight(1f),
                    ) { Text("♪ Zerlegt", maxLines = 1) }
                }
            }
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("▶  Weiter", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
        // Built-in chord types are short; uploaded recordings may carry longer names.
        label.length <= 6 -> Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        else -> Text(
            label,
            fontSize = if (label.length <= 12) 16.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The whole game: home screen, own runs & recordings, and the timed run. [extra] is the
 * platform's own section under the game settings (the app gate on Android); [audioStorage]
 * enables uploading recordings. The host routes its back button through [RunControl.back].
 */
@Composable
fun GameApp(
    scores: Scores,
    control: RunControl = remember { RunControl() },
    audioStorage: AudioStorage? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val library = remember(scores) { Library(scores).also { scores.applySoundSettings() } }
    if (control.running) {
        Run(scores, library, onExit = { control.running = false })
        return
    }
    when (val page = control.page) {
        GamePage.Home -> Home(
            scores,
            library,
            onRun = { control.running = true },
            onLibrary = { control.page = GamePage.Library },
            extra = extra,
        )
        GamePage.Library -> LibraryScreen(
            library,
            audioStorage,
            onEditRun = { control.page = GamePage.EditRun(it) },
            onExit = { control.page = GamePage.Home },
        )
        is GamePage.EditRun -> RunEditor(
            library,
            runId = page.runId,
            onSaved = { run, isNew ->
                if (isNew) {
                    scores.selectedRunId = run.id
                    control.page = GamePage.Home
                } else {
                    control.page = GamePage.Library
                }
            },
            onCancel = { control.page = GamePage.Library },
        )
    }
}

/** Screens inside [GameApp] besides the timed run. */
sealed interface GamePage {
    data object Home : GamePage
    data object Library : GamePage
    /** [runId] null creates a new run. */
    data class EditRun(val runId: String?) : GamePage
}

/** Lets the host see and leave a run or sub-screen (Android back button). */
class RunControl {
    var running by mutableStateOf(false)
    var page by mutableStateOf<GamePage>(GamePage.Home)

    val canGoBack: Boolean get() = running || page != GamePage.Home

    fun back() {
        when {
            running -> running = false
            page is GamePage.EditRun -> page = GamePage.Library
            else -> page = GamePage.Home
        }
    }
}

@Composable
private fun Home(
    scores: Scores,
    library: Library,
    onRun: () -> Unit,
    onLibrary: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit,
) {
    var maxErrors by remember { mutableIntStateOf(scores.maxErrors) }
    var missDelaySec by remember { mutableIntStateOf(scores.missDelaySec) }
    var runSeconds by remember { mutableIntStateOf(scores.runSeconds) }
    var chordMs by remember { mutableIntStateOf(scores.chordMs) }
    var mode by remember { mutableStateOf(scores.gameMode) }
    val runs = remember { library.runs }
    var runId by remember { mutableStateOf(scores.selectedRunId.takeIf { id -> runs.any { it.id == id } }.orEmpty()) }
    val target = remember(mode, runId) { library.target(runId, mode) }
    val pending = if (target is RunTarget.Builtin) ReviewQueue.decode(scores.reviewQueue).pendingCount(mode) else 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HolzAffe", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("${RunLength.format(runSeconds)} · Gehörbildung im Puzzle-Rush")
        RunPicker(
            mode = mode,
            selectedRunId = runId,
            runs = runs,
            onSelectMode = {
                mode = it
                runId = ""
                scores.gameMode = it
                scores.selectedRunId = ""
            },
            onSelectRun = {
                runId = it.id
                scores.selectedRunId = it.id
            },
        )
        OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) {
            Text("Eigene Runs & Audios")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "All-time High · ${target.label} · ${RunLength.format(runSeconds)}",
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("${scores.bestScore(target, runSeconds)}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Text("Aktuelle Serie ${scores.streak} · längste Serie ${scores.bestStreak}")
        if (target is RunTarget.Custom) {
            Text(
                "${target.items.size} Aufgabe(n) im Run",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Run-Länge", Modifier.weight(1f))
            TextButton(onClick = { runSeconds = RunLength.step(runSeconds, up = false); scores.runSeconds = runSeconds }) {
                Text("−", fontSize = 22.sp)
            }
            Text(RunLength.format(runSeconds), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { runSeconds = RunLength.step(runSeconds, up = true); scores.runSeconds = runSeconds }) {
                Text("+", fontSize = 22.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Akkord-Dauer")
                Text(
                    "letzter Akkord klingt doppelt so lang",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
            TextButton(onClick = { ChordPlayer.play(SOUND_SAMPLE) }) { Text("▶", fontSize = 18.sp) }
            TextButton(onClick = { chordMs = ChordLength.step(chordMs, up = false); scores.chordMs = chordMs }) {
                Text("−", fontSize = 22.sp)
            }
            Text(ChordLength.format(chordMs), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { chordMs = ChordLength.step(chordMs, up = true); scores.chordMs = chordMs }) {
                Text("+", fontSize = 22.sp)
            }
        }
        Stepper(
            if (maxErrors == 0) "Abbruch nach Fehlern: aus" else "Abbruch nach Fehlern",
            maxErrors, 0..10,
        ) { maxErrors = it; scores.maxErrors = it }
        Stepper("Fehler-Anzeige (Sek.)", missDelaySec, 1..10) {
            missDelaySec = it
            scores.missDelaySec = it
        }
        extra()
    }
}

/** Own runs (create, edit, delete) and uploaded recordings (upload, name, play, delete). */
@Composable
private fun LibraryScreen(
    library: Library,
    audioStorage: AudioStorage?,
    onEditRun: (runId: String?) -> Unit,
    onExit: () -> Unit,
) {
    var runs by remember { mutableStateOf(library.runs) }
    var audios by remember { mutableStateOf(library.audios) }
    var editingAudioId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf(false) }

    fun reload() {
        runs = library.runs
        audios = library.audios
    }

    fun commitRename() {
        editingAudioId?.let { library.renameAudio(it, editingName) }
        editingAudioId = null
        reload()
    }

    DisposableEffect(Unit) { onDispose { ChordPlayer.stop() } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Eigene Runs & Audios", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Eigene Runs", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Stell dir Runs aus beliebigen Akkordfolgen, Kadenzen, Akkordtypen, Umkehrungen und deinen Audios zusammen.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.75f),
            )
            runs.forEach { run ->
                val count = library.items(run, audios).size
                LibraryRow(
                    title = run.name,
                    subtitle = "$count Aufgabe(n)",
                ) {
                    TextButton(onClick = { onEditRun(run.id) }) { Text("✎") }
                    TextButton(onClick = {
                        if (confirmDelete == run.id) {
                            library.deleteRun(run.id)
                            confirmDelete = null
                            reload()
                        } else {
                            confirmDelete = run.id
                        }
                    }) { Text(if (confirmDelete == run.id) "Sicher?" else "🗑", color = if (confirmDelete == run.id) Bad else Color.White) }
                }
            }
            Button(onClick = { onEditRun(null) }, modifier = Modifier.fillMaxWidth()) { Text("＋ Neuer Run") }

            HorizontalSpacer()
            Text("Eigene Audios", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Lade Aufnahmen hoch und gib ihnen Namen – im Run wählst du dann den richtigen Namen aus. " +
                    (audioStorage?.formatsHint ?: ""),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.75f),
            )
            if (audios.size == 1) {
                Text(
                    "Tipp: Mit mindestens 2 Audios gibt es auch falsche Antworten zur Auswahl.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            audios.forEach { audio ->
                if (editingAudioId == audio.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            singleLine = true,
                            label = { Text("Name") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { commitRename() }, enabled = editingName.isNotBlank()) { Text("OK") }
                    }
                    if (audios.any { it.id != audio.id && it.name.equals(editingName.trim(), ignoreCase = true) }) {
                        Text("Diesen Namen gibt es schon – nimm lieber einen eindeutigen.", fontSize = 12.sp, color = Bad)
                    }
                } else {
                    LibraryRow(title = audio.name, subtitle = "Eigenes Audio") {
                        TextButton(onClick = { ChordPlayer.playFile(audio.path) }) { Text("▶") }
                        TextButton(onClick = {
                            commitRename()
                            editingAudioId = audio.id
                            editingName = audio.name
                        }) { Text("✎") }
                        TextButton(onClick = {
                            if (confirmDelete == audio.id) {
                                library.deleteAudio(audio.id)?.let { audioStorage?.delete(it.path) }
                                confirmDelete = null
                                reload()
                            } else {
                                confirmDelete = audio.id
                            }
                        }) { Text(if (confirmDelete == audio.id) "Sicher?" else "🗑", color = if (confirmDelete == audio.id) Bad else Color.White) }
                    }
                }
            }
            if (importError) {
                Text("Die Datei konnte nicht übernommen werden.", fontSize = 13.sp, color = Bad)
            }
            if (audioStorage != null) {
                Button(
                    onClick = {
                        commitRename()
                        importError = false
                        audioStorage.pick { imported ->
                            if (imported == null) return@pick
                            val name = imported.suggestedName.trim().ifEmpty { "Audio ${audios.size + 1}" }
                            val audio = CustomAudio(Library.newId("au"), name, imported.path)
                            library.addAudio(audio)
                            reload()
                            // Name it right away.
                            editingAudioId = audio.id
                            editingName = name
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("＋ Audio hochladen") }
            } else {
                Text("Hochladen ist auf diesem Gerät nicht verfügbar.", fontSize = 13.sp, color = Bad)
            }
        }
        Button(onClick = { commitRename(); onExit() }, modifier = Modifier.fillMaxWidth()) { Text("Fertig") }
    }
}

@Composable
private fun HorizontalSpacer() = Spacer(Modifier.height(8.dp))

@Composable
private fun LibraryRow(title: String, subtitle: String, actions: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            }
            actions()
        }
    }
}

/** Create or edit an own run: a name plus any built-in exercises and uploaded recordings. */
@Composable
private fun RunEditor(
    library: Library,
    runId: String?,
    onSaved: (run: CustomRun, isNew: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val existing = remember(runId) { runId?.let { id -> library.runs.firstOrNull { it.id == id } } }
    val audios = remember { library.audios }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var selected by remember { mutableStateOf(existing?.itemIds?.toSet().orEmpty()) }
    val groups = remember(audios) {
        listOf(ChecklistGroup("Eigene Audios", audios.map { RunItem.Audio(it) })) + builtinChecklistGroups(GameMode.MIX)
    }
    val orderedIds = remember(groups) { groups.flatMap { g -> g.items.map { it.id } } }
    val selectedCount = orderedIds.count { it in selected }

    DisposableEffect(Unit) { onDispose { ChordPlayer.stop() } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (existing == null) "Neuer Run" else "Run bearbeiten", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name des Runs") },
            placeholder = { Text("z. B. Pop-Folgen") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (selectedCount == 0) "Wähle mindestens eine Aufgabe aus." else "$selectedCount ausgewählt",
            fontSize = 13.sp,
            color = if (selectedCount == 0) Bad else MaterialTheme.colorScheme.primary,
        )
        if (audios.isEmpty()) {
            Text(
                "Eigene Audios lädst du unter „Eigene Runs & Audios“ hoch.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        ItemChecklist(
            groups = groups,
            isChecked = { it in selected },
            onChange = { ids, checked -> selected = if (checked) selected + ids else selected - ids },
            modifier = Modifier.weight(1f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Abbrechen") }
            Button(
                onClick = {
                    val run = CustomRun(
                        id = existing?.id ?: Library.newId("run"),
                        name = name.trim(),
                        itemIds = orderedIds.filter { it in selected },
                    )
                    library.saveRun(run)
                    onSaved(run, existing == null)
                },
                enabled = name.isNotBlank() && selectedCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text("Speichern") }
        }
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
private fun Run(scores: Scores, library: Library, onExit: () -> Unit) {
    var round by remember { mutableIntStateOf(0) } // bumping it restarts the run
    val target = remember(round) { library.target(scores.selectedRunId, scores.gameMode) }
    val seconds = remember(round) { scores.runSeconds }
    val mode = (target as? RunTarget.Builtin)?.mode ?: GameMode.MIX
    val deck = remember(round) {
        (target as? RunTarget.Custom)?.let { CustomRunDeck(it.items, allAudios = library.audios) }
    }
    val nextQuestion = remember(deck) { deck?.let { d -> { d.nextQuestion() } } }
    var paused by remember(round) { mutableStateOf(false) }
    var remainingMs by remember(round) { mutableLongStateOf(seconds * 1000L) }
    val left = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    var score by remember(round) { mutableIntStateOf(0) }
    // The streak carries over from earlier runs; only a miss resets it.
    var streak by remember(round) { mutableIntStateOf(scores.streak) }
    var bestStreak by remember(round) { mutableIntStateOf(streak) }
    var errors by remember(round) { mutableIntStateOf(0) }
    val previousBest = remember(round) { scores.bestScore(target, seconds) }
    val previousStreak = remember(round) { scores.bestStreak }
    val maxErrors = remember(round) { scores.maxErrors } // 0 = errors never end the run
    val failed = maxErrors > 0 && errors >= maxErrors
    val over = remainingMs <= 0L || failed

    LaunchedEffect(round, paused) {
        if (paused) return@LaunchedEffect
        var lastMark = TimeSource.Monotonic.markNow()
        while (remainingMs > 0L && !(maxErrors > 0 && errors >= maxErrors)) {
            delay(100)
            val now = TimeSource.Monotonic.markNow()
            remainingMs = (remainingMs - (now - lastMark).inWholeMilliseconds).coerceAtLeast(0L)
            lastMark = now
        }
    }
    LaunchedEffect(over) {
        if (over) {
            paused = false
            ChordPlayer.stop()
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    RunLength.format(left),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        paused -> MaterialTheme.colorScheme.primary
                        left <= 10 -> Bad
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (paused) {
                    Text(
                        "PAUSIERT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Serie $streak", fontSize = 26.sp, color = if (streak >= 5) Good else MaterialTheme.colorScheme.onSurface)
                if (maxErrors > 0) Text("Fehler $errors / $maxErrors", color = Bad)
            }
        }
        Text("$score", fontSize = 84.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "${target.label} · Bestwert $previousBest · Serie-Rekord $previousStreak",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                QuizPanel(
                    active = true,
                    scores = scores,
                    mode = mode,
                    paused = paused,
                    onPausedChange = { paused = it },
                    isTimed = true,
                    nextQuestion = nextQuestion,
                    onAnswer = { ok ->
                        deck?.record(ok)
                        if (ok) {
                            score++
                            streak++
                            if (streak > bestStreak) bestStreak = streak
                            // Saved per answer so leaving mid-run keeps a record.
                            if (score > scores.bestScore(target, seconds)) scores.setBestScore(target, seconds, score)
                            scores.streak = streak
                            if (streak > scores.bestStreak) scores.bestStreak = streak
                        } else {
                            streak = 0
                            scores.streak = 0
                            errors++
                        }
                    },
                )
            }
            TextButton(onClick = onExit) { Text("Abbrechen") }
        }
    }
}
