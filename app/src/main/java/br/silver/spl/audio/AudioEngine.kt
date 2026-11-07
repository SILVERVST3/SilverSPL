package br.silver.spl.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import br.silver.spl.dsp.Analyzer

enum class InputSource(val androidId: Int, val label: String, val nominalOffset: Double, val nominalNote: String) {
    UNPROCESSED(
        MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED (sem processamento)", 130.0,
        "CDD Android: 94 dB SPL @1 kHz = −36 dBFS"
    ),
    VOICE_RECOGNITION(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 112.35,
        "CDD Android: 90 dB SPL @1 kHz = −22,35 dBFS"
    ),
    MIC(
        MediaRecorder.AudioSource.MIC, "MIC (padrão, pode ter AGC)", 112.35,
        "Sem padrão definido; calibre com decibelímetro"
    );
}

/** Captura do microfone em float 48 kHz numa thread de áudio, entregando blocos ao Analyzer. */
class AudioEngine(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()

    var analyzer: Analyzer? = null
        private set
    var sampleRate = 48000
        private set
    var activeSource: InputSource = InputSource.UNPROCESSED
        private set
    var lastError: String? = null
        private set
    var routedDeviceName: String = ""
        private set

    val isRunning: Boolean get() = running

    fun unprocessedSupported(): Boolean =
        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    fun inputDevices(): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }

    /** Inicia a captura. Retorna o Analyzer ou null em caso de erro (ver [lastError]). */
    @SuppressLint("MissingPermission")
    fun start(source: InputSource, preferred: AudioDeviceInfo?): Analyzer? {
        stop()
        lastError = null
        val candidates = listOf(48000, 44100)
        var rec: AudioRecord? = null
        var usedRate = 48000
        var usedSource = source
        val sources = if (source == InputSource.UNPROCESSED) listOf(source, InputSource.VOICE_RECOGNITION) else listOf(source)
        loop@ for (src in sources) {
            for (rate in candidates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                if (minBuf <= 0) continue
                val bufBytes = maxOf(minBuf * 2, rate / 5 * 4)
                try {
                    val r = AudioRecord(src.androidId, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufBytes)
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r; usedRate = rate; usedSource = src
                        break@loop
                    } else r.release()
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
        }
        val r = rec ?: run {
            lastError = lastError ?: "Não foi possível abrir o microfone"
            return null
        }
        if (preferred != null) {
            try { r.setPreferredDevice(preferred) } catch (_: Exception) {}
        }
        disableEffects(r.audioSessionId)
        try {
            r.startRecording()
        } catch (e: Exception) {
            lastError = e.message ?: "Falha ao iniciar a gravação"
            r.release(); releaseEffects()
            return null
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            lastError = "O microfone está em uso por outro app?"
            r.release(); releaseEffects()
            return null
        }
        record = r
        sampleRate = usedRate
        activeSource = usedSource
        if (usedSource != source) lastError = "UNPROCESSED indisponível — usando VOICE_RECOGNITION"
        routedDeviceName = try {
            r.routedDevice?.let { describe(it) } ?: ""
        } catch (_: Exception) { "" }
        val an = Analyzer(usedRate)
        analyzer = an
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(960)
            while (running) {
                val n = try { r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) } catch (_: Exception) { -1 }
                if (n > 0) an.process(buf, n)
                else if (n < 0) { try { Thread.sleep(10) } catch (_: InterruptedException) {} }
            }
        }, "SilverSPL-audio").also { it.start() }
        return an
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        releaseEffects()
    }

    /** Microfones ativos informados pelo HAL (API 28+). Pode vir vazio. */
    fun activeMicrophones(): List<MicrophoneInfo> {
        val r = record ?: return emptyList()
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        return try { r.activeMicrophones ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun allMicrophones(): List<MicrophoneInfo> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        return try { am.microphones ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun disableEffects(session: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = false; effects.add(it) }
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = false; effects.add(it) }
        } catch (_: Exception) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects.add(it) }
        } catch (_: Exception) {}
    }

    private fun releaseEffects() {
        for (e in effects) try { e.release() } catch (_: Exception) {}
        effects.clear()
    }

    companion object {
        fun typeName(t: Int): String = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone interno"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset com fio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telefonia"
            else -> "Tipo $t"
        }

        fun describe(d: AudioDeviceInfo): String {
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
            val addr = if (Build.VERSION.SDK_INT >= 28) d.address ?: "" else ""
            return listOf(typeName(d.type), name, addr).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }

        fun deviceKey(d: AudioDeviceInfo?): String =
            if (d == null || d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "builtin" else "t${d.type}_${d.productName}"
    }
}
