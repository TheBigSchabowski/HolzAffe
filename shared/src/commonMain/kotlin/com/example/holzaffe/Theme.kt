package com.example.holzaffe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Refined dark-navy and warm-gold palette shared by Desktop and Android. */
object AppColors {
    val BgDeep = Color(0xFF0F1222)
    val SurfaceCard = Color(0xFF1A1F38)
    val SurfaceCardAlt = Color(0xFF232948)
    val BorderSubtle = Color(0xFF2E365C)
    val AccentGold = Color(0xFFE8AE35)
    val AccentGoldSoft = Color(0x26E8AE35)
    val OnAccent = Color(0xFF14172B)
    val TextPrimary = Color(0xFFF3F5FC)
    val TextSecondary = Color(0xFFA6B0CF)
    val Good = Color(0xFF3FB950)
    val GoodSoft = Color(0x243FB950)
    val Bad = Color(0xFFE5534B)
    val BadSoft = Color(0x24E5534B)
}

val Good = AppColors.Good
val Bad = AppColors.Bad

private val Colors = darkColorScheme(
    primary = AppColors.AccentGold,
    onPrimary = AppColors.OnAccent,
    background = AppColors.BgDeep,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.BgDeep,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceCard,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.BorderSubtle,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

/** Rounded card container with subtle border (or gold highlight) used across all tabs. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceCard),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) AppColors.AccentGold.copy(alpha = 0.55f) else AppColors.BorderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Single-select rounded toggle button used by beat-length, key-mode and relock selectors. */
@Composable
fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) AppColors.AccentGold else AppColors.BorderSubtle,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AppColors.AccentGold else AppColors.SurfaceCardAlt,
            contentColor = if (selected) AppColors.OnAccent else AppColors.TextPrimary,
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

/**
 * Multi-select chip showing a checkmark when active and an optional secondary subtitle line
 * (e.g. item count for custom runs). When [onLongClick] is supplied, long-pressing triggers
 * haptic feedback and opens the item's editor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingStar: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val bg = if (selected) AppColors.AccentGoldSoft else AppColors.SurfaceCardAlt
    val border = if (selected) AppColors.AccentGold else AppColors.BorderSubtle
    val titleColor = if (selected) AppColors.AccentGold else AppColors.TextPrimary
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = if (subtitle == null) 44.dp else 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(clickModifier),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (leadingStar) "★ $label" else label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = AppColors.AccentGold,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Clean, compact stepper row with circular − / + buttons and a steady value pill. */
@Composable
fun CompactStepper(
    label: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = AppColors.TextSecondary)
            }
        }
        StepIconButton("−", enabled = canDecrement, onClick = onDecrement)
        Text(
            text = valueText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.defaultMinSize(minWidth = 64.dp),
        )
        StepIconButton("+", enabled = canIncrement, onClick = onIncrement)
    }
}

@Composable
private fun StepIconButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = AppColors.SurfaceCardAlt,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary.copy(alpha = 0.4f),
            )
        }
    }
}

/** Integer stepper backed by [CompactStepper] for settings like maxErrors or gate counts. */
@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    CompactStepper(
        label = label,
        valueText = "$value",
        canDecrement = value > range.first,
        canIncrement = value < range.last,
        onDecrement = { onChange((value - 1).coerceIn(range)) },
        onIncrement = { onChange((value + 1).coerceIn(range)) },
    )
}

/** Pill-style segmented switch inside a dark track (e.g. Runs vs. Audios or Global vs. Custom tempo). */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.BgDeep,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { idx, label ->
                val active = idx == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(idx) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) AppColors.AccentGold else Color.Transparent,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) AppColors.OnAccent else AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Consistent top header with back button for all subscreens (RunEditor, Settings, GateScreens). */
@Composable
fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(AppIcons.Back, contentDescription = "Zurück", tint = AppColors.TextPrimary)
        }
        Text(
            text = title,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Clickable summary row leading into a subscreen (e.g. blocked apps or chord exercise filter). */
@Composable
fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.SurfaceCardAlt,
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!badge.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppColors.AccentGoldSoft,
                ) {
                    Text(
                        badge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.AccentGold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Confirmation dialog replacing inline "Sicher?" text swaps on delete buttons. */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceCard,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 14.sp) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Bad, contentColor = Color.White),
            ) {
                Text("Löschen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = AppColors.TextSecondary)
            }
        },
    )
}

/**
 * BPM + time-signature selector replacing the old raw milliseconds stepper.
 * Lets the user adjust BPM with either −/+ steps or a slider, pick the beat value (`1/4`..`4/4`),
 * see the derived duration in seconds, and play a 4-chord sample with ▶ Probe.
 */
@Composable
fun TempoControlSection(
    tempo: ChordTempo,
    onChange: (ChordTempo) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactStepper(
            label = "Tempo (BPM)",
            subtitle = "≈ ${ChordLength.format(tempo.chordMs)} pro Akkord · letzter doppelt",
            valueText = "${tempo.bpm} BPM",
            canDecrement = tempo.bpm > ChordTempo.MIN_BPM,
            canIncrement = tempo.bpm < ChordTempo.MAX_BPM,
            onDecrement = { onChange(tempo.copy(bpm = ChordTempo.stepBpm(tempo.bpm, up = false))) },
            onIncrement = { onChange(tempo.copy(bpm = ChordTempo.stepBpm(tempo.bpm, up = true))) },
        )
        Slider(
            value = tempo.bpm.toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw / ChordTempo.STEP_BPM).roundToInt() * ChordTempo.STEP_BPM)
                    .coerceIn(ChordTempo.MIN_BPM, ChordTempo.MAX_BPM)
                if (snapped != tempo.bpm) onChange(tempo.copy(bpm = snapped))
            },
            valueRange = ChordTempo.MIN_BPM.toFloat()..ChordTempo.MAX_BPM.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AppColors.AccentGold,
                activeTrackColor = AppColors.AccentGold,
                inactiveTrackColor = AppColors.SurfaceCardAlt,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Text("Takt / Akkord-Länge", fontSize = 13.sp, color = AppColors.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChordBeatLength.entries.forEach { beat ->
                val selected = beat == tempo.beatLength
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onChange(tempo.copy(beatLength = beat)) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) AppColors.AccentGold else AppColors.SurfaceCardAlt,
                    border = BorderStroke(1.dp, if (selected) AppColors.AccentGold else AppColors.BorderSubtle),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            beat.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) AppColors.OnAccent else AppColors.TextPrimary,
                        )
                        Text(
                            beat.subtitle,
                            fontSize = 10.sp,
                            color = if (selected) AppColors.OnAccent.copy(alpha = 0.8f) else AppColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onPreview,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AppColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(AppIcons.Play, contentDescription = null, tint = AppColors.AccentGold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Probe anhören (${tempo.formatShort()})", fontSize = 13.sp, color = AppColors.TextPrimary)
        }
    }
}

/**
 * Modal dialog wrapping [TempoControlSection] so BPM & beat length can be edited directly from
 * Speedrun or Settings without navigating away.
 */
@Composable
fun TempoDialog(
    tempo: ChordTempo,
    title: String = "Tempo & Takt",
    subtitle: String? = null,
    onChange: (ChordTempo) -> Unit,
    onPreview: (ChordTempo) -> Unit,
    onResetToDefault: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceCard,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TempoControlSection(
                    tempo = tempo,
                    onChange = onChange,
                    onPreview = { onPreview(tempo) },
                )
                if (onResetToDefault != null) {
                    TextButton(onClick = onResetToDefault, modifier = Modifier.align(Alignment.End)) {
                        Text("Standard-Tempo nutzen", fontSize = 12.sp, color = AppColors.TextSecondary)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Fertig")
            }
        },
    )
}
