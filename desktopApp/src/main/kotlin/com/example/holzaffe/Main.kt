package com.example.holzaffe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID
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

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)
    override fun getString(key: String, default: String): String = prefs.get(key, default)
    override fun putString(key: String, value: String) = prefs.put(key, value)
}

/** Copies picked recordings into the per-user data folder (macOS Application Support, Linux XDG). */
private class DesktopAudioStorage : AudioStorage {
    private val dir: File by lazy {
        val home = System.getProperty("user.home")
        val base = if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
            File(home, "Library/Application Support/HolzAffe")
        } else {
            File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "holzaffe")
        }
        File(base, "audio").apply { mkdirs() }
    }

    override val formatsHint = "Am Computer spielen WAV- und AIFF-Dateien."

    override fun pick(onPicked: (ImportedAudio?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Audio auswählen", FileDialog.LOAD)
        dialog.isVisible = true // modal
        val name = dialog.file ?: return onPicked(null)
        val source = File(dialog.directory, name)
        val imported = runCatching {
            val ext = source.extension.lowercase().ifEmpty { "wav" }
            val target = File(dir, "${UUID.randomUUID()}.$ext")
            source.copyTo(target)
            ImportedAudio(target.absolutePath, source.nameWithoutExtension)
        }.getOrNull()
        onPicked(imported)
    }

    override fun delete(path: String) {
        runCatching { File(path).takeIf { it.parentFile == dir }?.delete() }
    }
}

fun main() = application {
    val scores = DesktopScores()
    val audioStorage = DesktopAudioStorage()
    Window(
        onCloseRequest = ::exitApplication,
        title = "HolzAffe",
        state = rememberWindowState(width = 460.dp, height = 820.dp),
    ) {
        AppTheme {
            Surface(Modifier.fillMaxSize()) {
                GameApp(scores, audioStorage = audioStorage)
            }
        }
    }
}
