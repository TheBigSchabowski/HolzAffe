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
