package com.example.holzaffe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, crisp vector icons built directly on a 24dp viewport so both Android and
 * Desktop render uniform icons without pulling in extra material-icons dependencies.
 */
object AppIcons {
    private val Stroke = SolidColor(Color.White)

    private fun strokeIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.strokePath(vararg coords: Pair<Float, Float>, close: Boolean = false) {
        if (coords.isEmpty()) return
        path(
            stroke = Stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(coords[0].first, coords[0].second)
            for (i in 1 until coords.size) lineTo(coords[i].first, coords[i].second)
            if (close) close()
        }
    }

    /** Lightning bolt for Speedrun tab and primary start CTA. */
    val Speedrun: ImageVector by lazy {
        strokeIcon("Speedrun") {
            strokePath(
                13f to 2f,
                4f to 14f,
                12f to 14f,
                11f to 22f,
                20f to 10f,
                12f to 10f,
                close = true,
            )
        }
    }

    /** Eighth-note pair for Runs & Music settings. */
    val Runs: ImageVector by lazy {
        strokeIcon("Runs") {
            strokePath(9f to 18f, 9f to 5f, 20f to 3f, 20f to 16f)
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 18f)
                arcTo(3f, 3f, 0f, true, true, 3f, 18f)
                arcTo(3f, 3f, 0f, true, true, 9f, 18f)
            }
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 16f)
                arcTo(3f, 3f, 0f, true, true, 14f, 16f)
                arcTo(3f, 3f, 0f, true, true, 20f, 16f)
            }
        }
    }

    /** Checklist icon for Aufgaben tab. */
    val Tasks: ImageVector by lazy {
        strokeIcon("Tasks") {
            strokePath(3.5f to 7f, 5.5f to 9f, 9f to 5f)
            strokePath(12f to 7f, 21f to 7f)
            strokePath(3.5f to 14f, 5.5f to 16f, 9f to 12f)
            strokePath(12f to 14f, 21f to 14f)
            strokePath(12f to 20f, 21f to 20f)
        }
    }

    /** Launch-arrow icon for Weiterleitungen tab. */
    val Redirects: ImageVector by lazy {
        strokeIcon("Redirects") {
            strokePath(14f to 4f, 20f to 4f, 20f to 10f)
            strokePath(10f to 14f, 20f to 4f)
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 13f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, false, true, 16f, 20f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 4f, 18f)
                verticalLineTo(8f)
                arcTo(2f, 2f, 0f, false, true, 6f, 6f)
                horizontalLineTo(11f)
            }
        }
    }

    /** Padlock icon for Android App-Sperre status pill. */
    val Lock: ImageVector by lazy {
        strokeIcon("Lock") {
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 11f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 13f)
                verticalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 17f, 21f)
                horizontalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 19f)
                verticalLineTo(13f)
                arcTo(2f, 2f, 0f, false, true, 7f, 11f)
                close()
            }
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 11f)
                verticalLineTo(7f)
                arcTo(4f, 4f, 0f, false, true, 16f, 7f)
                verticalLineTo(11f)
            }
        }
    }

    /** Three-sliders icon for Settings. */
    val Settings: ImageVector by lazy {
        strokeIcon("Settings") {
            strokePath(4f to 6f, 20f to 6f)
            strokePath(9f to 3.5f, 9f to 8.5f)
            strokePath(4f to 12f, 20f to 12f)
            strokePath(15f to 9.5f, 15f to 14.5f)
            strokePath(4f to 18f, 20f to 18f)
            strokePath(10f to 15.5f, 10f to 20.5f)
        }
    }

    /** Filled play triangle for audio previews and starting runs. */
    val Play: ImageVector by lazy {
        strokeIcon("Play") {
            path(fill = Stroke, pathFillType = PathFillType.NonZero) {
                moveTo(7f, 5f)
                lineTo(19f, 12f)
                lineTo(7f, 19f)
                close()
            }
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus") {
            strokePath(12f to 5f, 12f to 19f)
            strokePath(5f to 12f, 19f to 12f)
        }
    }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit") {
            strokePath(4f to 20f, 5f to 15f, 16.5f to 3.5f, 20.5f to 7.5f, 9f to 19f, close = true)
            strokePath(14f to 6f, 18f to 10f)
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            strokePath(4f to 7f, 20f to 7f)
            strokePath(9f to 7f, 9f to 4f, 15f to 4f, 15f to 7f)
            strokePath(6f to 7f, 7f to 20f, 17f to 20f, 18f to 7f)
            strokePath(10f to 11f, 10f to 16f)
            strokePath(14f to 11f, 14f to 16f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            strokePath(4.5f to 12.5f, 9.5f to 17.5f, 19.5f to 6.5f)
        }
    }

    val Back: ImageVector by lazy {
        strokeIcon("Back") {
            strokePath(19f to 12f, 5f to 12f)
            strokePath(11f to 6f, 5f to 12f, 11f to 18f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            strokePath(9f to 6f, 15f to 12f, 9f to 18f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") {
            strokePath(6f to 9f, 12f to 15f, 18f to 9f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            strokePath(6f to 6f, 18f to 18f)
            strokePath(18f to 6f, 6f to 18f)
        }
    }

    /** Phone icon representing an app redirect. */
    val App: ImageVector by lazy {
        strokeIcon("App") {
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 3f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 5f)
                verticalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 17f, 21f)
                horizontalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 19f)
                verticalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 7f, 3f)
                close()
            }
            strokePath(11f to 18f, 13f to 18f)
        }
    }

    /** Grid icon representing an Android widget. */
    val Widget: ImageVector by lazy {
        strokeIcon("Widget") {
            strokePath(4f to 4f, 10f to 4f, 10f to 10f, 4f to 10f, close = true)
            strokePath(14f to 4f, 20f to 4f, 20f to 10f, 14f to 10f, close = true)
            strokePath(4f to 14f, 10f to 14f, 10f to 20f, 4f to 20f, close = true)
            strokePath(14f to 14f, 20f to 14f, 20f to 20f, 14f to 20f, close = true)
        }
    }

    /** Clock icon representing gate timers and weekly schedules. */
    val Clock: ImageVector by lazy {
        strokeIcon("Clock") {
            path(stroke = Stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, true, true, 3f, 12f)
                arcTo(9f, 9f, 0f, true, true, 21f, 12f)
            }
            strokePath(12f to 7f, 12f to 12f, 15.5f to 14f)
        }
    }
}
