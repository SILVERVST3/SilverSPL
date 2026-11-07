package br.silver.spl.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

enum class Signal(val label: String) { SINE("Seno"), PINK("Ruído rosa"), WHITE("Ruído branco") }

/** Gerador de sinais de teste (saída na mídia atual: alto-falante, fone, Bluetooth, USB). */
class ToneGenerator {
    @Volatile var signal = Signal.SINE
    @Volatile var frequency = 1000.0
    @Volatile var levelDbfs = -12.0
    @Volatile private var running = false
    private var thread: Thread? = null
    val isPlaying: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "SilverSPL-tone").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(400) } catch (_: InterruptedException) {} }
        thread = null
    }

    private fun loop() {
        val fs = 48000
        val minBuf = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, fs / 10 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) { running = false; return }
        track.play()
        val buf = FloatArray(1024)
        var phase = 0.0
        var gainNow = 0.0
        val rnd = java.util.Random()
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        while (running) {
            val target = Math.pow(10.0, levelDbfs / 20.0)
            val sig = signal
            val inc = 2.0 * PI * frequency / fs
            for (i in buf.indices) {
                gainNow += (target - gainNow) * 0.002 // rampa suave, sem cliques
                val v = when (sig) {
                    Signal.SINE -> { phase += inc; if (phase > 2 * PI) phase -= 2 * PI; sin(phase) * 1.41421356 }
                    Signal.WHITE -> rnd.nextGaussian()
                    Signal.PINK -> {
                        val w = rnd.nextGaussian()
                        b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759
                        b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856
                        b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
                        val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
                        b6 = w * 0.115926
                        p * PINK_NORM
                    }
                }
                // levelDbfs = nível RMS; limita para não estourar
                buf[i] = (v * gainNow).coerceIn(-1.0, 1.0).toFloat()
            }
            track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
        try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
        track.release()
    }

    companion object {
        /** Normaliza o ruído rosa (filtro de Paul Kellet) para RMS = 1. */
        const val PINK_NORM = 0.3281
    }
}
