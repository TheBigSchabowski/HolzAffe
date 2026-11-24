package com.example.holzaffe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer

actual object ChordPlayer {
    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null

    @Synchronized
    actual fun play(chords: List<List<Int>>) = playPcm(Synth.render(chords))

    @Synchronized
    actual fun playArpeggiated(chords: List<List<Int>>) = playPcm(Synth.renderArpeggiated(chords))

    @Synchronized
    actual fun playFile(path: String) {
        stop()
        // A broken or unsupported upload must never crash a run or the app gate.
        media = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(path)
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun playPcm(pcm: ShortArray) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Synth.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    @Synchronized
    actual fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        media?.let {
            runCatching { it.stop() }
            it.release()
        }
        media = null
    }
}
