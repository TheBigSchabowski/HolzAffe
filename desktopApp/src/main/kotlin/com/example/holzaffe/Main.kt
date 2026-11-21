package com.example.holzaffe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.prefs.Preferences

/** High score and settings in the user's Java preferences. */
private class DesktopScores : Scores {
    private val prefs = Preferences.userRoot().node("com/example/holzaffe")
    override var highScore: Int
        get() = prefs.getInt("highScore", 0)
        set(v) = prefs.putInt("highScore", v)
    override var streak: Int
        get() = prefs.getInt("streak", 0)
        set(v) = prefs.putInt("streak", v)
    override var bestStreak: Int
        get() = prefs.getInt("bestStreak", 0)
        set(v) = prefs.putInt("bestStreak", v)
    override var maxErrors: Int
        get() = prefs.getInt("maxErrors", 0)
        set(v) = prefs.putInt("maxErrors", v)
    override var missDelaySec: Int
        get() = prefs.getInt("missDelaySec", DEFAULT_MISS_DELAY_SEC).coerceIn(1, 10)
        set(v) = prefs.putInt("missDelaySec", v.coerceIn(1, 10))
    override var reviewQueue: String
        get() = prefs.get("reviewQueue", "")
        set(v) = prefs.put("reviewQueue", v)
    override var gameMode: GameMode
        get() = GameMode.fromKey(prefs.get("gameMode", null))
        set(v) = prefs.put("gameMode", v.key)

    override fun highScore(mode: GameMode): Int = prefs.getInt(highScoreKey(mode), 0)

    override fun setHighScore(mode: GameMode, value: Int) {
        prefs.putInt(highScoreKey(mode), value)
    }

    private fun highScoreKey(mode: GameMode): String =
        if (mode == GameMode.DEGREES) "highScore" else "highScore:${mode.key}"
}

fun main() = application {
    val scores = DesktopScores()
    Window(
        onCloseRequest = ::exitApplication,
        title = "HolzAffe",
        state = rememberWindowState(width = 440.dp, height = 780.dp),
    ) {
        AppTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(24.dp)) { GameApp(scores) }
            }
        }
    }
}
