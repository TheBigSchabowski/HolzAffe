package com.example.holzaffe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** C-Dur → F-Dur → G-Dur → C-Dur: lets the user hear the BPM/beat-length setting. */
val SOUND_SAMPLE = listOf(listOf(48, 52, 55), listOf(48, 53, 57), listOf(47, 50, 55), listOf(48, 52, 55))

/** Top-level navigation tabs. Desktop shows only [SPEEDRUN] and [RUNS]; Android shows all four. */
enum class AppTab(val label: String, val weight: Float) {
    SPEEDRUN("Speedrun", 1.0f),
    RUNS("Runs", 0.76f),
    TASKS("Aufgaben", 1.0f),
    REDIRECTS("Weiterleitungen", 1.44f),
}

/** Subscreens inside [GameApp] besides the main tabbed shell and the active timed run. */
sealed interface GamePage {
    data object Main : GamePage
    /** [runId] null creates a new custom run; optional [prefillMode] pre-selects that mode's exercises. */
    data class EditRun(val runId: String?, val prefillMode: GameMode? = null) : GamePage
    /** [chordId] null creates a new synthesized custom chord or chord progression. */
    data class EditChord(val chordId: String?) : GamePage
    data object Settings : GamePage
}

/** Shared navigation and refresh controller so the host (e.g. Android back button) can drive [GameApp]. */
class RunControl {
    var running by mutableStateOf(false)
    var tab by mutableStateOf(AppTab.SPEEDRUN)
    var page by mutableStateOf<GamePage>(GamePage.Main)
    /** Bumped after creating, editing, or deleting runs/chords/audios so every tab refreshes immediately. */
    var libraryRevision by mutableIntStateOf(0)

    val canGoBack: Boolean get() = running || page != GamePage.Main || tab != AppTab.SPEEDRUN

    fun back() {
        when {
            running -> running = false
            page != GamePage.Main -> page = GamePage.Main
            tab != AppTab.SPEEDRUN -> tab = AppTab.SPEEDRUN
        }
    }
}

/**
 * Multi-select grid for the 4 built-in modes plus all user-created custom runs.
 * Reused by both the Speedrun tab and the Android app-gate run selector.
 * Supports optional long-press callbacks so users can jump straight to editing a run or mode.
 */
@Composable
fun MultiRunPicker(
    selectedTokens: Set<String>,
    runs: List<CustomRun>,
    onToggleToken: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemCountOf: (CustomRun) -> Int = { it.itemIds.size },
    onResetToModes: ((Set<String>) -> Unit)? = null,
    onCreateRun: (() -> Unit)? = null,
    onLongClickRun: ((CustomRun) -> Unit)? = null,
    onLongClickMode: ((GameMode) -> Unit)? = null,
) {
    val allModeTokens = remember { GameMode.PLAYABLE.map { RunSelectionCodec.modeToken(it) }.toSet() }
    val allModesActive = allModeTokens.all { it in selectedTokens } && selectedTokens.size == allModeTokens.size
    val hasLongPress = onLongClickRun != null || onLongClickMode != null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Runs auswählen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    if (hasLongPress) "Tippen = auswählen · Lange drücken = bearbeiten" else "Mehrere wählbar – Auswahlen werden gemischt",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
            }
            if (onResetToModes != null) {
                TextButton(
                    onClick = {
                        val next = if (allModesActive) {
                            setOf(RunSelectionCodec.modeToken(GameMode.DEGREES))
                        } else {
                            allModeTokens
                        }
                        onResetToModes(next)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(if (allModesActive) "Nur Stufen" else "Alle 4 Modi", fontSize = 12.sp, color = AppColors.AccentGold)
                }
            }
        }

        // 2x2 grid of the 4 playable built-in ear-training modes.
        val modeRows = listOf(
            listOf(GameMode.DEGREES to "Stufen", GameMode.CHORD_TYPES to "Akkordtypen"),
            listOf(GameMode.CADENCES to "Kadenzen", GameMode.INVERSIONS to "Umkehrungen"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modeRows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (mode, label) ->
                        val token = RunSelectionCodec.modeToken(mode)
                        SelectableChip(
                            label = label,
                            selected = token in selectedTokens,
                            onClick = { onToggleToken(token) },
                            onLongClick = onLongClickMode?.let { cb -> { cb(mode) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (runs.isNotEmpty() || onCreateRun != null) {
            HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.7f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Eigene Runs",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (onCreateRun != null) {
                    TextButton(
                        onClick = onCreateRun,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(AppIcons.Plus, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Neu", fontSize = 12.sp, color = AppColors.AccentGold)
                    }
                }
            }
            if (runs.isEmpty()) {
                Text(
                    "Noch keine eigenen Runs – erstelle über „+ Neu“ oder durch langes Drücken auf einen Modus eigene Mischungen.",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
            } else {
                runs.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { run ->
                            val token = RunSelectionCodec.runToken(run.id)
                            val tempoSuffix = run.customTempo?.let { " · ${it.bpm} BPM" }.orEmpty()
                            SelectableChip(
                                label = run.name,
                                subtitle = "${itemCountOf(run)} Aufg.$tempoSuffix",
                                leadingStar = true,
                                selected = token in selectedTokens,
                                onClick = { onToggleToken(token) },
                                onLongClick = onLongClickRun?.let { cb -> { cb(run) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
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
 * Collapsible grouped checkboxes with "Alle"/"Keine" per group and a ▶ preview per item.
 * Groups with checked items start expanded so the user sees active selections at a glance.
 */
@Composable
fun ItemChecklist(
    groups: List<ChecklistGroup>,
    isChecked: (String) -> Boolean,
    onChange: (ids: Set<String>, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nonEmpty = remember(groups) { groups.filter { it.items.isNotEmpty() } }
    var expandedMap by remember(nonEmpty) {
        val withChecked = nonEmpty.associate { g ->
            g.title to g.items.any { isChecked(it.id) }
        }
        val anyOpen = withChecked.values.any { it }
        val initial = if (anyOpen || nonEmpty.isEmpty()) {
            withChecked
        } else {
            withChecked.toMutableMap().apply { put(nonEmpty.first().title, true) }
        }
        mutableStateOf(initial)
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nonEmpty.forEach { group ->
            val ids = group.items.map { it.id }.toSet()
            val checkedCount = ids.count(isChecked)
            val expanded = expandedMap[group.title] == true
            item(key = "header:${group.title}") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expandedMap = expandedMap + (group.title to !expanded) },
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.SurfaceCard,
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "${group.title} · $checkedCount/${ids.size}",
                            Modifier.weight(1f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (checkedCount > 0) AppColors.TextPrimary else AppColors.TextSecondary,
                        )
                        TextButton(
                            onClick = {
                                onChange(ids, true)
                                expandedMap = expandedMap + (group.title to true)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text("Alle", fontSize = 12.sp, color = AppColors.AccentGold) }
                        TextButton(
                            onClick = { onChange(ids, false) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text("Keine", fontSize = 12.sp, color = AppColors.TextSecondary) }
                    }
                }
            }
            if (expanded) {
                items(group.items, key = { "${group.title}:${it.id}" }) { item ->
                    val checked = isChecked(item.id)
                    val toggle = { onChange(setOf(item.id), !checked) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = toggle)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Text(
                            item.label,
                            Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { item.preview() }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                AppIcons.Play,
                                contentDescription = "Anhören",
                                tint = AppColors.AccentGold,
                                modifier = Modifier.size(16.dp),
                            )
                        }
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
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("▶ Nochmal", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { ChordPlayer.playArpeggiated(question.chords) },
                enabled = active && !question.isAudio,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("♪ Zerlegt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { onPausedChange(true) },
                enabled = active,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) { Text("⏸ Check", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }

        question.choices.indices.chunked(3).forEach { rowIndices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowIndices.forEach { i ->
                    val label = question.choices[i]
                    val color = when {
                        picked == null -> AppColors.SurfaceCardAlt
                        i == question.correct -> Good
                        i == picked -> Bad
                        else -> AppColors.SurfaceCardAlt
                    }
                    val borderColor = when {
                        picked == null -> AppColors.BorderSubtle
                        i == question.correct -> Good
                        i == picked -> Bad
                        else -> AppColors.BorderSubtle
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
                        border = BorderStroke(1.dp, borderColor),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(70.dp),
                    ) {
                        ChoiceLabel(label)
                    }
                }
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
    SectionCard(modifier = modifier, highlighted = true) {
        Column(
            Modifier.fillMaxWidth(),
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
                                containerColor = if (active) MaterialTheme.colorScheme.primary else AppColors.BgDeep,
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
                Text("Eigenes Audio", fontSize = 14.sp, color = AppColors.TextSecondary)
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
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
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
                                color = AppColors.TextSecondary,
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
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                    modifier = Modifier.weight(1f),
                ) { Text(if (question.isAudio) "▶ Abspielen" else "▶ Alle", maxLines = 1) }
                if (!question.isAudio) {
                    OutlinedButton(
                        onClick = { ChordPlayer.playArpeggiated(question.chords) },
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
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
 * The main HolzAffe shell:
 * - Android displays 4 tabs (`Speedrun`, `Runs`, `Aufgaben`, `Weiterleitungen`),
 * - Desktop displays 2 tabs (`Speedrun`, `Runs`).
 */
@Composable
fun GameApp(
    scores: Scores,
    control: RunControl = remember { RunControl() },
    audioStorage: AudioStorage? = null,
    showAndroidTabs: Boolean = false,
    topBarExtra: @Composable () -> Unit = {},
    runsGateContent: @Composable (libraryRevision: Int) -> Unit = {},
    tasksTabContent: @Composable () -> Unit = {},
    redirectsTabContent: @Composable () -> Unit = {},
    settingsExtra: @Composable () -> Unit = {},
) {
    val library = remember(scores) { Library(scores).also { scores.applySoundSettings() } }
    if (control.running) {
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Run(scores, library, onExit = { control.running = false })
        }
        return
    }
    when (val page = control.page) {
        is GamePage.EditRun -> RunEditor(
            scores = scores,
            library = library,
            runId = page.runId,
            prefillMode = page.prefillMode,
            onSaved = { run, isNew ->
                if (isNew) {
                    scores.speedrunSelection = setOf(RunSelectionCodec.runToken(run.id))
                }
                control.libraryRevision++
                control.page = GamePage.Main
            },
            onDeleted = { deletedId ->
                val cleaned = scores.speedrunSelection - RunSelectionCodec.runToken(deletedId)
                if (cleaned.isNotEmpty()) scores.speedrunSelection = cleaned
                control.libraryRevision++
                control.page = GamePage.Main
            },
            onCancel = { control.page = GamePage.Main },
        )
        is GamePage.EditChord -> ChordEditor(
            scores = scores,
            library = library,
            chordId = page.chordId,
            onSaved = {
                control.libraryRevision++
                control.page = GamePage.Main
            },
            onDeleted = {
                control.libraryRevision++
                control.page = GamePage.Main
            },
            onCancel = { control.page = GamePage.Main },
        )
        GamePage.Settings -> SettingsScreen(
            scores = scores,
            revision = control.libraryRevision,
            onTempoChanged = { control.libraryRevision++ },
            onBack = { control.page = GamePage.Main },
            extra = settingsExtra,
        )
        GamePage.Main -> {
            val tabs = if (showAndroidTabs) AppTab.entries else listOf(AppTab.SPEEDRUN, AppTab.RUNS)
            if (control.tab !in tabs) control.tab = AppTab.SPEEDRUN

            Column(Modifier.fillMaxSize()) {
                MainTopBar(
                    title = if (control.tab == AppTab.SPEEDRUN) "HolzAffe" else control.tab.label,
                    extra = topBarExtra,
                    onSettings = { control.page = GamePage.Settings },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (control.tab) {
                        AppTab.SPEEDRUN -> SpeedrunTab(
                            scores = scores,
                            library = library,
                            revision = control.libraryRevision,
                            onRun = { control.running = true },
                            onEditRun = { id, prefill -> control.page = GamePage.EditRun(id, prefill) },
                            onChanged = { control.libraryRevision++ },
                        )
                        AppTab.RUNS -> RunsTab(
                            scores = scores,
                            library = library,
                            revision = control.libraryRevision,
                            audioStorage = audioStorage,
                            onEditRun = { control.page = GamePage.EditRun(it) },
                            onEditChord = { control.page = GamePage.EditChord(it) },
                            onChanged = { control.libraryRevision++ },
                            gateContent = { runsGateContent(control.libraryRevision) },
                        )
                        AppTab.TASKS -> tasksTabContent()
                        AppTab.REDIRECTS -> redirectsTabContent()
                    }
                }
                BottomNavBar(
                    tabs = tabs,
                    selected = control.tab,
                    onSelect = { control.tab = it },
                )
            }
        }
    }
}

@Composable
private fun MainTopBar(
    title: String,
    extra: @Composable () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = AppColors.BgDeep) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                extra()
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSettings),
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.SurfaceCard,
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AppIcons.Settings,
                            contentDescription = "Einstellungen",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun BottomNavBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Surface(color = AppColors.SurfaceCard) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = AppColors.BorderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val active = tab == selected
                    val icon: ImageVector = when (tab) {
                        AppTab.SPEEDRUN -> AppIcons.Speedrun
                        AppTab.RUNS -> AppIcons.Runs
                        AppTab.TASKS -> AppIcons.Tasks
                        AppTab.REDIRECTS -> AppIcons.Redirects
                    }
                    val tint = if (active) AppColors.AccentGold else AppColors.TextSecondary
                    Column(
                        modifier = Modifier
                            .weight(tab.weight)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(tab) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) AppColors.AccentGoldSoft else Color.Transparent,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .size(20.dp),
                            )
                        }
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Primary practice screen: shows the high score & streak for the active selection, lets the user
 * adjust run length AND BPM/tact directly (via [TempoDialog]), launch the Speedrun immediately,
 * combine multiple runs/modes, and long-press any run or mode to edit it.
 */
@Composable
private fun SpeedrunTab(
    scores: Scores,
    library: Library,
    revision: Int,
    onRun: () -> Unit,
    onEditRun: (runId: String?, prefillMode: GameMode?) -> Unit,
    onChanged: () -> Unit,
) {
    var runSeconds by remember { mutableIntStateOf(scores.runSeconds) }
    var selection by remember(revision) { mutableStateOf(scores.speedrunSelection) }
    val runs = remember(revision) { library.runs }
    val audios = remember(revision) { library.audios }
    val customChords = remember(revision) { library.customChords }
    val target = remember(selection, revision) { library.resolveSelection(selection, scores.gameMode) }
    val globalTempo = remember(revision) { scores.chordTempo }
    val singleCustomRun = (target as? RunTarget.Custom)?.run
    val effectiveTempo = remember(target, globalTempo) {
        singleCustomRun?.customTempo ?: globalTempo
    }
    val pending = remember(target, scores.reviewQueue) {
        if (target is RunTarget.Builtin) ReviewQueue.decode(scores.reviewQueue).pendingCount(target.mode) else 0
    }
    val itemCount = when (target) {
        is RunTarget.Custom -> target.items.size
        is RunTarget.Combined -> target.items.size
        is RunTarget.Builtin -> null
    }
    var showTempoDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { ChordPlayer.stop() } }

    if (showTempoDialog) {
        val customRun = singleCustomRun?.takeIf { it.customTempo != null }
        var dialogTempo by remember(showTempoDialog) { mutableStateOf(effectiveTempo) }
        TempoDialog(
            tempo = dialogTempo,
            title = if (customRun != null) "Tempo für „${customRun.name}“" else "Tempo & Takt",
            subtitle = if (customRun != null) {
                "Eigene Geschwindigkeit nur für diesen Run"
            } else {
                "Gilt für Speedrun und App-Sperre (Standard)"
            },
            onChange = { next ->
                dialogTempo = next
                if (customRun != null) {
                    library.saveRun(customRun.copy(bpm = next.bpm, beats = next.beatLength.beats))
                } else {
                    scores.chordTempo = next
                }
                onChanged()
            },
            onPreview = { previewTempo ->
                Synth.chordMs = previewTempo.chordMs
                ChordPlayer.play(SOUND_SAMPLE)
            },
            onResetToDefault = if (customRun != null) {
                {
                    library.saveRun(customRun.copy(bpm = null, beats = null))
                    scores.applySoundSettings()
                    onChanged()
                    showTempoDialog = false
                }
            } else {
                null
            },
            onDismiss = { showTempoDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(highlighted = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = target.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showTempoDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = AppColors.AccentGoldSoft,
                    border = BorderStroke(1.dp, AppColors.AccentGold.copy(alpha = 0.4f)),
                ) {
                    Text(
                        effectiveTempo.formatShort(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.AccentGold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${scores.bestScore(target, runSeconds)}",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.AccentGold,
                        lineHeight = 56.sp,
                    )
                    Text(
                        text = "Bestwert · ${RunLength.format(runSeconds)}",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Serie ${scores.streak}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (scores.streak >= 5) Good else AppColors.TextPrimary,
                    )
                    Text(
                        text = "Rekord ${scores.bestStreak}",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                    if (itemCount != null) {
                        Text("$itemCount Aufgabe(n)", fontSize = 12.sp, color = AppColors.AccentGold)
                    }
                    if (pending > 0) {
                        Text("$pending Wiederholung(en)", fontSize = 12.sp, color = AppColors.AccentGold)
                    }
                }
            }

            HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))

            CompactStepper(
                label = "Run-Länge",
                valueText = RunLength.format(runSeconds),
                canDecrement = runSeconds > RunLength.MIN,
                canIncrement = runSeconds < RunLength.MAX,
                onDecrement = {
                    runSeconds = RunLength.step(runSeconds, up = false)
                    scores.runSeconds = runSeconds
                },
                onIncrement = {
                    runSeconds = RunLength.step(runSeconds, up = true)
                    scores.runSeconds = runSeconds
                },
            )

            // Direct Tempo & Tact access right on the Speedrun card.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showTempoDialog = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (singleCustomRun?.customTempo != null) "Run-Tempo" else "Tempo & Takt",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        effectiveTempo.formatDetailed(),
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                }
                IconButton(
                    onClick = {
                        Synth.chordMs = effectiveTempo.chordMs
                        ChordPlayer.play(SOUND_SAMPLE)
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = "Probe", tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                }
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = AppColors.SurfaceCardAlt,
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                ) {
                    Text(
                        "Ändern",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.AccentGold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Button(
                onClick = {
                    ChordPlayer.stop()
                    onRun()
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speedrun starten", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        SectionCard {
            MultiRunPicker(
                selectedTokens = selection,
                runs = runs,
                itemCountOf = { library.items(it, audios, customChords).size },
                onToggleToken = { token ->
                    val next = RunSelectionCodec.toggle(selection, token)
                    selection = next
                    scores.speedrunSelection = next
                },
                onResetToModes = { next ->
                    selection = next
                    scores.speedrunSelection = next
                },
                onCreateRun = { onEditRun(null, null) },
                onLongClickRun = { run -> onEditRun(run.id, null) },
                onLongClickMode = { mode -> onEditRun(null, mode) },
            )
        }
    }
}

/**
 * Runs configuration tab: compact BPM & beat-length tempo card, custom runs / custom synthesized
 * chords / uploaded audios CRUD, plus on Android the app-gate ear-training selector ([gateContent]).
 */
@Composable
private fun RunsTab(
    scores: Scores,
    library: Library,
    revision: Int,
    audioStorage: AudioStorage?,
    onEditRun: (runId: String?) -> Unit,
    onEditChord: (chordId: String?) -> Unit,
    onChanged: () -> Unit,
    gateContent: @Composable () -> Unit,
) {
    var tempo by remember(revision) { mutableStateOf(scores.chordTempo) }
    var runs by remember(revision) { mutableStateOf(library.runs) }
    var chords by remember(revision) { mutableStateOf(library.customChords) }
    var audios by remember(revision) { mutableStateOf(library.audios) }
    var subTab by remember { mutableIntStateOf(0) }
    var showTempoExpanded by remember { mutableStateOf(false) }
    var editingAudioId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var deleteRunCandidate by remember { mutableStateOf<CustomRun?>(null) }
    var deleteChordCandidate by remember { mutableStateOf<CustomChordExercise?>(null) }
    var deleteAudioCandidate by remember { mutableStateOf<CustomAudio?>(null) }
    var importError by remember { mutableStateOf(false) }

    fun reload() {
        runs = library.runs
        chords = library.customChords
        audios = library.audios
        onChanged()
    }

    fun commitRename() {
        editingAudioId?.let { library.renameAudio(it, editingName) }
        editingAudioId = null
        reload()
    }

    DisposableEffect(Unit) { onDispose { ChordPlayer.stop() } }

    deleteRunCandidate?.let { run ->
        ConfirmDeleteDialog(
            title = "Run löschen?",
            message = "„${run.name}“ wird dauerhaft entfernt.",
            onConfirm = {
                library.deleteRun(run.id)
                val cleaned = scores.speedrunSelection - RunSelectionCodec.runToken(run.id)
                if (cleaned.isNotEmpty()) scores.speedrunSelection = cleaned
                deleteRunCandidate = null
                reload()
            },
            onDismiss = { deleteRunCandidate = null },
        )
    }

    deleteChordCandidate?.let { chord ->
        ConfirmDeleteDialog(
            title = "Akkord löschen?",
            message = "„${chord.name}“ wird aus der App und aus allen Runs entfernt.",
            onConfirm = {
                library.deleteCustomChord(chord.id)
                deleteChordCandidate = null
                reload()
            },
            onDismiss = { deleteChordCandidate = null },
        )
    }

    deleteAudioCandidate?.let { audio ->
        ConfirmDeleteDialog(
            title = "Audio löschen?",
            message = "„${audio.name}“ wird aus der App und aus allen Runs entfernt.",
            onConfirm = {
                library.deleteAudio(audio.id)?.let { audioStorage?.delete(it.path) }
                deleteAudioCandidate = null
                reload()
            },
            onDismiss = { deleteAudioCandidate = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showTempoExpanded = !showTempoExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Standard-Tempo & Takt", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Text(tempo.formatDetailed(), fontSize = 12.sp, color = AppColors.AccentGold)
                }
                IconButton(
                    onClick = {
                        Synth.chordMs = tempo.chordMs
                        ChordPlayer.play(SOUND_SAMPLE)
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = "Probe", tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                }
                Icon(
                    imageVector = if (showTempoExpanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (showTempoExpanded) {
                TempoControlSection(
                    tempo = tempo,
                    onChange = { next ->
                        tempo = next
                        scores.chordTempo = next
                    },
                    onPreview = {
                        Synth.chordMs = tempo.chordMs
                        ChordPlayer.play(SOUND_SAMPLE)
                    },
                )
            }
        }

        SectionCard {
            Text("Eigene Inhalte", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            SegmentedToggle(
                options = listOf("Runs (${runs.size})", "Akkorde (${chords.size})", "Audios (${audios.size})"),
                selectedIndex = subTab,
                onSelect = {
                    commitRename()
                    subTab = it
                },
            )
            when (subTab) {
                0 -> {
                    Text(
                        "Stelle Runs aus beliebigen Standard-Übungen, eigenen Akkorden und Audios zusammen.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                    if (runs.isEmpty()) {
                        Text(
                            "Noch keine eigenen Runs erstellt.",
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        runs.forEach { run ->
                            val count = library.items(run, audios, chords).size
                            val tempoLabel = run.customTempo?.let { " · ${it.formatShort()}" } ?: " · Standard-Tempo"
                            LibraryRow(
                                title = run.name,
                                subtitle = "$count Aufgabe(n)$tempoLabel",
                            ) {
                                IconButton(onClick = { onEditRun(run.id) }, modifier = Modifier.size(36.dp)) {
                                    Icon(AppIcons.Edit, contentDescription = "Bearbeiten", tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { deleteRunCandidate = run }, modifier = Modifier.size(36.dp)) {
                                    Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { onEditRun(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Neuer Run", fontWeight = FontWeight.SemiBold)
                    }
                }

                1 -> {
                    Text(
                        "Baue eigene Akkorde oder 2–4-Akkord-Folgen (inkl. 9er, m7♭5, dim7, Umkehrungen) – mit Synth, Zerlegt & Check-Modus.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                    if (chords.size == 1) {
                        Text(
                            "Tipp: Mit mindestens 2 eigenen Akkorden erscheinen auch falsche Antworten zur Auswahl.",
                            fontSize = 12.sp,
                            color = AppColors.AccentGold,
                        )
                    }
                    chords.forEach { chord ->
                        val countText = if (chord.chords.size == 1) "1 Akkord" else "${chord.chords.size} Akkorde"
                        val keyText = chord.fixedRootMidi?.let { "fest (${ChordSpec.noteLabel(it)})" } ?: "transponiert"
                        LibraryRow(title = chord.name, subtitle = "$countText · $keyText") {
                            IconButton(
                                onClick = { RunItem.CustomChord(chord).preview() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(AppIcons.Play, contentDescription = "Anhören", tint = AppColors.AccentGold, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onEditChord(chord.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(AppIcons.Edit, contentDescription = "Bearbeiten", tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { deleteChordCandidate = chord }, modifier = Modifier.size(36.dp)) {
                                Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Button(
                        onClick = { onEditChord(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Eigener Akkord / Folge", fontWeight = FontWeight.SemiBold)
                    }
                }

                else -> {
                    Text(
                        "Lade eigene Aufnahmen hoch und gib ihnen Namen – im Run wählst du den richtigen Namen aus. " +
                            (audioStorage?.formatsHint ?: ""),
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                    if (audios.size == 1) {
                        Text(
                            "Tipp: Mit mindestens 2 Audios gibt es auch falsche Antworten zur Auswahl.",
                            fontSize = 12.sp,
                            color = AppColors.AccentGold,
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
                                IconButton(onClick = { ChordPlayer.playFile(audio.path) }, modifier = Modifier.size(36.dp)) {
                                    Icon(AppIcons.Play, contentDescription = "Abspielen", tint = AppColors.AccentGold, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = {
                                        commitRename()
                                        editingAudioId = audio.id
                                        editingName = audio.name
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(AppIcons.Edit, contentDescription = "Umbenennen", tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { deleteAudioCandidate = audio }, modifier = Modifier.size(36.dp)) {
                                    Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(18.dp))
                                }
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
                                    editingAudioId = audio.id
                                    editingName = name
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Audio hochladen", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("Hochladen ist auf diesem Gerät nicht verfügbar.", fontSize = 13.sp, color = Bad)
                    }
                }
            }
        }

        gateContent()
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, actions: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.SurfaceCardAlt,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
    }
}

/** Create or edit an own run: a name, optional per-run BPM/tact tempo, and any exercises/chords/recordings. */
@Composable
private fun RunEditor(
    scores: Scores,
    library: Library,
    runId: String?,
    prefillMode: GameMode? = null,
    onSaved: (run: CustomRun, isNew: Boolean) -> Unit,
    onDeleted: (runId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val existing = remember(runId) { runId?.let { id -> library.runs.firstOrNull { it.id == id } } }
    val audios = remember { library.audios }
    val customChords = remember { library.customChords }
    val globalTempo = remember { scores.chordTempo }
    var name by remember {
        mutableStateOf(
            existing?.name ?: prefillMode?.let { "Meine ${it.label}" }.orEmpty()
        )
    }
    var selected by remember {
        mutableStateOf(
            existing?.itemIds?.toSet()
                ?: prefillMode?.let { EXERCISES_BY_MODE.getValue(it).map { ex -> ex.id }.toSet() }
                ?: emptySet()
        )
    }
    var useCustomTempo by remember { mutableStateOf(existing?.customTempo != null) }
    var customTempo by remember { mutableStateOf(existing?.customTempo ?: globalTempo) }
    var confirmDelete by remember { mutableStateOf(false) }

    val groups = remember(audios, customChords) {
        listOf(
            ChecklistGroup("Eigene Akkorde", customChords.map { RunItem.CustomChord(it) }),
            ChecklistGroup("Eigene Audios", audios.map { RunItem.Audio(it) }),
        ) + builtinChecklistGroups(GameMode.MIX)
    }
    val orderedIds = remember(groups) { groups.flatMap { g -> g.items.map { it.id } } }
    val selectedCount = orderedIds.count { it in selected }

    DisposableEffect(Unit) {
        onDispose {
            ChordPlayer.stop()
            scores.applySoundSettings()
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDeleteDialog(
            title = "Run löschen?",
            message = "„${existing.name}“ wird dauerhaft entfernt.",
            onConfirm = {
                library.deleteRun(existing.id)
                confirmDelete = false
                onDeleted(existing.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubScreenTopBar(
            title = if (existing == null) "Neuer Run" else "Run bearbeiten",
            onBack = onCancel,
            trailing = {
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(38.dp)) {
                        Icon(AppIcons.Trash, contentDescription = "Run löschen", tint = Bad, modifier = Modifier.size(20.dp))
                    }
                }
            },
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name des Runs") },
            placeholder = { Text("z. B. Pop-Folgen") },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(padding = PaddingValues(12.dp)) {
            Text("Akkord-Tempo dieses Runs", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            SegmentedToggle(
                options = listOf("Standard (${globalTempo.formatShort()})", "Eigenes Tempo"),
                selectedIndex = if (useCustomTempo) 1 else 0,
                onSelect = { useCustomTempo = it == 1 },
            )
            if (useCustomTempo) {
                TempoControlSection(
                    tempo = customTempo,
                    onChange = { customTempo = it },
                    onPreview = {
                        Synth.chordMs = customTempo.chordMs
                        ChordPlayer.play(SOUND_SAMPLE)
                    },
                )
            }
        }
        Text(
            if (selectedCount == 0) "Wähle mindestens eine Aufgabe aus." else "$selectedCount Aufgabe(n) ausgewählt",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selectedCount == 0) Bad else AppColors.AccentGold,
        )
        if (customChords.isEmpty() && audios.isEmpty()) {
            Text(
                "Eigene Akkorde & Audios legst du im Tab „Runs → Eigene Inhalte“ an.",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
        }
        ItemChecklist(
            groups = groups,
            isChecked = { it in selected },
            onChange = { ids, checked -> selected = if (checked) selected + ids else selected - ids },
            modifier = Modifier.weight(1f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                modifier = Modifier.weight(1f),
            ) { Text("Abbrechen") }
            Button(
                onClick = {
                    val run = CustomRun(
                        id = existing?.id ?: Library.newId("run"),
                        name = name.trim(),
                        itemIds = orderedIds.filter { it in selected },
                        bpm = if (useCustomTempo) customTempo.bpm else null,
                        beats = if (useCustomTempo) customTempo.beatLength.beats else null,
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

/**
 * Builder screen for user-defined synthesized chords and 1–4 chord progressions.
 * Supports 19 chord qualities, inversions, transposing or fixed-key voicing, and live preview.
 */
@Composable
private fun ChordEditor(
    scores: Scores,
    library: Library,
    chordId: String?,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onCancel: () -> Unit,
) {
    val existing = remember(chordId) { chordId?.let { id -> library.customChords.firstOrNull { it.id == id } } }
    var specs by remember {
        mutableStateOf(existing?.chords ?: listOf(ChordSpec(0, CustomChordQuality.MAJ7, 0)))
    }
    var fixedRootMidi by remember { mutableStateOf(existing?.fixedRootMidi) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var manualName by remember { mutableStateOf(existing != null) }
    var name by remember {
        mutableStateOf(
            existing?.name ?: CustomChordExercise.suggestName(specs, fixedRootMidi)
        )
    }
    var confirmDelete by remember { mutableStateOf(false) }

    val safeIdx = activeIndex.coerceIn(0, specs.lastIndex)
    val currentSpec = specs[safeIdx]

    fun updateSpecs(next: List<ChordSpec>, nextFixed: Int? = fixedRootMidi) {
        specs = next
        fixedRootMidi = nextFixed
        if (!manualName) {
            name = CustomChordExercise.suggestName(next, nextFixed)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ChordPlayer.stop()
            scores.applySoundSettings()
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDeleteDialog(
            title = "Akkord löschen?",
            message = "„${existing.name}“ wird aus der App und aus allen Runs entfernt.",
            onConfirm = {
                library.deleteCustomChord(existing.id)
                confirmDelete = false
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubScreenTopBar(
            title = if (existing == null) "Neuer Akkord / Folge" else "Akkord bearbeiten",
            onBack = onCancel,
            trailing = {
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(38.dp)) {
                        Icon(AppIcons.Trash, contentDescription = "Löschen", tint = Bad, modifier = Modifier.size(20.dp))
                    }
                }
            },
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(highlighted = true, padding = PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Akkordfolge (${specs.size}/4)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (specs.size > 1) {
                        TextButton(
                            onClick = {
                                val next = specs.toMutableList().apply { removeAt(safeIdx) }
                                activeIndex = (safeIdx - 1).coerceAtLeast(0)
                                updateSpecs(next)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Akkord ${safeIdx + 1} entfernen", fontSize = 12.sp, color = Bad)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    specs.forEachIndexed { idx, spec ->
                        val shortLabel = if (fixedRootMidi != null) {
                            ChordSpec.noteLabel(fixedRootMidi!! + spec.rootOffset)
                        } else {
                            ChordSpec.degreeLabel(spec.rootOffset)
                        } + if (spec.quality == CustomChordQuality.MAJ) "" else spec.quality.label
                        ChipButton(
                            label = "${idx + 1}. $shortLabel",
                            selected = idx == safeIdx,
                            onClick = { activeIndex = idx },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (specs.size < 4) {
                        OutlinedButton(
                            onClick = {
                                val next = specs + currentSpec.copy(rootOffset = (currentSpec.rootOffset + 5) % 12)
                                activeIndex = next.lastIndex
                                updateSpecs(next)
                            },
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, AppColors.AccentGold.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(38.dp),
                        ) {
                            Icon(AppIcons.Plus, contentDescription = "Akkord hinzufügen", tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val previewExercise = CustomChordExercise("preview", name, specs, fixedRootMidi)
                        val (midiChords, _) = previewExercise.buildMidiAndInfos(fixedRootMidi ?: 48)
                        ChordPlayer.play(midiChords)
                    },
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Anhören (${scores.chordTempo.formatShort()})", fontSize = 13.sp, color = AppColors.TextPrimary)
                }
            }

            SectionCard(padding = PaddingValues(14.dp)) {
                Text("Tonart", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                SegmentedToggle(
                    options = listOf("Transponiert", "Feste Tonart"),
                    selectedIndex = if (fixedRootMidi == null) 0 else 1,
                    onSelect = { idx ->
                        val nextFixed = if (idx == 0) null else (fixedRootMidi ?: 48)
                        updateSpecs(specs, nextFixed)
                    },
                )
                if (fixedRootMidi != null) {
                    Text("Bezugston / Grundton der Tonart", fontSize = 12.sp, color = AppColors.TextSecondary)
                    (0..11).chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { pc ->
                                ChipButton(
                                    label = ChordSpec.noteLabel(pc),
                                    selected = (48 + pc) == fixedRootMidi,
                                    onClick = { updateSpecs(specs, 48 + pc) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))

                Text(
                    if (fixedRootMidi == null) "Stufe von Akkord ${safeIdx + 1}" else "Grundton von Akkord ${safeIdx + 1}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                (0..11).chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { offset ->
                            val label = if (fixedRootMidi != null) {
                                ChordSpec.noteLabel(fixedRootMidi!! + offset)
                            } else {
                                ChordSpec.degreeLabel(offset)
                            }
                            ChipButton(
                                label = label,
                                selected = currentSpec.rootOffset == offset,
                                onClick = {
                                    val updated = specs.toMutableList().apply {
                                        this[safeIdx] = currentSpec.copy(rootOffset = offset)
                                    }
                                    updateSpecs(updated)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))

                Text("Akkordtyp", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                CustomChordQuality.entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { quality ->
                            ChipButton(
                                label = quality.label,
                                selected = currentSpec.quality == quality,
                                onClick = {
                                    val updated = specs.toMutableList().apply {
                                        this[safeIdx] = currentSpec.copy(quality = quality)
                                    }
                                    updateSpecs(updated)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                HorizontalDivider(color = AppColors.BorderSubtle.copy(alpha = 0.65f))

                Text("Umkehrung / Lage", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Grund", 1 to "1. Umk", 2 to "2. Umk").forEach { (inv, label) ->
                        ChipButton(
                            label = label,
                            selected = currentSpec.inversion == inv,
                            onClick = {
                                val updated = specs.toMutableList().apply {
                                    this[safeIdx] = currentSpec.copy(inversion = inv)
                                }
                                updateSpecs(updated)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    manualName = true
                    name = it
                },
                singleLine = true,
                label = { Text("Name / Antwort-Label") },
                placeholder = { Text("z. B. I → ♭III → IV") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                modifier = Modifier.weight(1f),
            ) { Text("Abbrechen") }
            Button(
                onClick = {
                    val saved = CustomChordExercise(
                        id = existing?.id ?: Library.newId("ch"),
                        name = name.trim(),
                        chords = specs,
                        fixedRootMidi = fixedRootMidi,
                    )
                    library.saveCustomChord(saved)
                    onSaved()
                },
                enabled = name.isNotBlank() && specs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Speichern") }
        }
    }
}

/** General settings subscreen reached from the top-bar ⚙ button. */
@Composable
private fun SettingsScreen(
    scores: Scores,
    revision: Int,
    onTempoChanged: () -> Unit,
    onBack: () -> Unit,
    extra: @Composable () -> Unit,
) {
    var runSeconds by remember { mutableIntStateOf(scores.runSeconds) }
    var maxErrors by remember { mutableIntStateOf(scores.maxErrors) }
    var missDelaySec by remember { mutableIntStateOf(scores.missDelaySec) }
    var tempo by remember(revision) { mutableStateOf(scores.chordTempo) }
    var showTempoDialog by remember { mutableStateOf(false) }

    if (showTempoDialog) {
        TempoDialog(
            tempo = tempo,
            onChange = { next ->
                tempo = next
                scores.chordTempo = next
                onTempoChanged()
            },
            onPreview = { previewTempo ->
                Synth.chordMs = previewTempo.chordMs
                ChordPlayer.play(SOUND_SAMPLE)
            },
            onDismiss = { showTempoDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubScreenTopBar(title = "Einstellungen", onBack = onBack)

        SectionCard {
            Text("Speedrun", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            CompactStepper(
                label = "Standard-Run-Länge",
                valueText = RunLength.format(runSeconds),
                canDecrement = runSeconds > RunLength.MIN,
                canIncrement = runSeconds < RunLength.MAX,
                onDecrement = {
                    runSeconds = RunLength.step(runSeconds, up = false)
                    scores.runSeconds = runSeconds
                },
                onIncrement = {
                    runSeconds = RunLength.step(runSeconds, up = true)
                    scores.runSeconds = runSeconds
                },
            )
            NavRow(
                title = "Tempo & Takt (BPM)",
                subtitle = tempo.formatDetailed(),
                badge = "Ändern",
                onClick = { showTempoDialog = true },
            )
            Stepper(
                if (maxErrors == 0) "Abbruch nach Fehlern: aus" else "Abbruch nach Fehlern",
                maxErrors,
                0..10,
            ) { maxErrors = it; scores.maxErrors = it }
        }

        SectionCard {
            Text("Feedback", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Stepper("Fehler-Anzeige (Sek.)", missDelaySec, 1..10) {
                missDelaySec = it
                scores.missDelaySec = it
            }
            Text(
                "So lange bleiben richtige und falsche Antwort nach einem Fehler farbig markiert.",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
        }

        extra()
    }
}

@Composable
private fun Run(scores: Scores, library: Library, onExit: () -> Unit) {
    var round by remember { mutableIntStateOf(0) } // bumping it restarts the run
    val target = remember(round) { library.resolveSelection(scores.speedrunSelection, scores.gameMode) }
    val seconds = remember(round) { scores.runSeconds }
    val mode = (target as? RunTarget.Builtin)?.mode ?: GameMode.MIX
    val deck = remember(round) {
        when (target) {
            is RunTarget.Custom -> CustomRunDeck(
                target.items,
                allAudios = library.audios,
                allCustomChords = library.customChords,
            )
            is RunTarget.Combined -> CustomRunDeck(
                target.items,
                allAudios = library.audios,
                allCustomChords = library.customChords,
            )
            is RunTarget.Builtin -> null
        }
    }
    val nextQuestion = remember(deck) { deck?.let { d -> { d.nextQuestion() } } }

    // Apply per-run tempo when playing a single custom run with custom BPM/beat settings.
    DisposableEffect(round) {
        val customTempo = (target as? RunTarget.Custom)?.run?.customTempo
        if (customTempo != null) Synth.chordMs = customTempo.chordMs else scores.applySoundSettings()
        onDispose { scores.applySoundSettings() }
    }

    var paused by remember(round) { mutableStateOf(false) }
    var remainingMs by remember(round) { mutableLongStateOf(seconds * 1000L) }
    val left = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    var score by remember(round) { mutableIntStateOf(0) }
    var streak by remember(round) { mutableIntStateOf(scores.streak) }
    var bestStreak by remember(round) { mutableIntStateOf(streak) }
    var errors by remember(round) { mutableIntStateOf(0) }
    val previousBest = remember(round) { scores.bestScore(target, seconds) }
    val previousStreak = remember(round) { scores.bestStreak }
    val maxErrors = remember(round) { scores.maxErrors }
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
            color = AppColors.TextSecondary,
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
