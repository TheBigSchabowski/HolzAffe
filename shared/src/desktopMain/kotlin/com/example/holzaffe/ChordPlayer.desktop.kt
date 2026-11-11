package com.example.holzaffe

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

actual object ChordPlayer {
    private var clip: Clip? = null

    @Synchronized
    actual fun play(chords: List<List<Int>>) {
        stop()
        val pcm = Synth.render(chords)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { // little-endian 16 bit
            bytes[2 * i] = pcm[i].toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        val format = AudioFormat(Synth.RATE.toFloat(), 16, 1, true, false)
        // No sound device (headless box) must not take the game down.
        clip = runCatching {
            AudioSystem.getClip().apply {
                open(format, bytes, 0, bytes.size)
                start()
            }
        }.getOrNull()
    }

    @Synchronized
    actual fun stop() {
        clip?.let {
            runCatching { it.stop() }
            it.close()
        }
        clip = null
    }
}
