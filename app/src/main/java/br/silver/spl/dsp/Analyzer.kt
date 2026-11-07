package br.silver.spl.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

import kotlin.math.pow

enum class AvgMode(val label: String, val tau: Double) {
    NONE("Méd: —", 0.0), FAST("Méd: R", 0.25), SLOW("Méd: L", 1.5), INF("Méd: ∞", -1.0)
}

/** Configuração imutável trocada pela UI; a thread de áudio detecta a mudança por referência. */
data class AnalyzerConfig(
    val curve: MicCurve = MicCurve.flat,
    val applyCurve: Boolean = true,
    val maxCorrectionDb: Double = 20.0,
    val offsetDb: Double = 130.0,
    val displayN: Int = 8192,
    val bandsPerOctave: Int = 3,
    val rtaWeighting: Weighting = Weighting.Z,
    val avgMode: AvgMode = AvgMode.FAST
)

class Snapshot {
    var valid = false
    var fs = 48000
    // níveis em dB SPL [A, C, Z]
    val fast = DoubleArray(3)
    val slow = DoubleArray(3)
    val leq = DoubleArray(3)
    val maxF = DoubleArray(3); val minF = DoubleArray(3)
    val maxS = DoubleArray(3); val minS = DoubleArray(3)
    var peakZ = -300.0
    var peakZMax = -300.0
    var clipping = false
    var elapsedSec = 0.0
    var rawDbfsZ = -300.0
    // espectro
    var bins = 0
    var binHz = 1.0
    var spectrum = DoubleArray(0)
    var spectrumPeak = DoubleArray(0)
    var bandCenters = DoubleArray(0)
    var bandLabels: Array<String> = emptyArray()
    var bands = DoubleArray(0)
    var bandsPeak = DoubleArray(0)
    var dominantHz = 0.0
}

/**
 * Motor de análise: recebe amostras float (±1.0 = fundo de escala) e calcula
 *  - SPL (A/C/Z, Fast/Slow, Leq, máx/mín, pico) a partir de STFT de 2048 pontos, salto de 1024;
 *  - espectro FFT de alta resolução e bandas de fração de oitava para exibição;
 *  - capturas de calibração (banda larga ou banda de 1/3 de oitava).
 * A correção da curva do microfone é aplicada no domínio da frequência (bin a bin).
 */
class Analyzer(val fs: Int) {
    companion object {
        const val HOP = 1024
        const val RING = 32768
        const val CAPTURE_N = 16384
    }

    @Volatile var config = AnalyzerConfig()

    private val ring = FloatArray(RING)
    private var ringPos = 0
    private var newSamples = 0
    private var hopCount = 0L
    private var blockPeak = 0.0f

    private val meter = SplMeter(fs)
    private var meterCfg: AnalyzerConfig? = null
    private val dt = HOP.toDouble() / fs
    private var peakMax = 0.0
    private var lastPeak = 0.0
    private var clipHold = 0
    private var hopSq = 0.0
    private var lastRawZ = 0.0

    // exibição
    private var dispSpec: PowerSpectrum? = null
    private var dispCfg: AnalyzerConfig? = null
    private var dispCorr = DoubleArray(0)
    private var dispAvg = DoubleArray(0)
    private var dispPeak = DoubleArray(0)
    private var avgCount = 0L
    private var bandsObj: FractionalBands? = null
    private var bandBuf = DoubleArray(0)
    private var bandPeakDb = DoubleArray(0)
    private var tmpPow = DoubleArray(0)
    @Volatile private var resetPeakRequest = false
    @Volatile private var resetStatsRequest = false
    @Volatile var frozen = false

    // captura de calibração
    private val capLock = Any()
    private var capMode = 0 // 0 = nada, 1 = banda larga, 2 = banda
    private var capF = 1000.0
    private var capSum = 0.0
    private var capFrames = 0
    private var capSpec: PowerSpectrum? = null

    private val snap = Snapshot()
    private val lock = Any()

    fun resetStats() { resetStatsRequest = true }
    fun resetPeakHold() { resetPeakRequest = true }

    fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val s = buf[i]
            ring[ringPos] = s
            ringPos = (ringPos + 1) and (RING - 1)
            val a = abs(s)
            if (a > blockPeak) blockPeak = a
            hopSq += s.toDouble() * s
            meter.push(s)
            newSamples++
            if (newSamples >= HOP) {
                newSamples = 0
                onHop()
            }
        }
    }

    private fun correctionFactor(cfg: AnalyzerConfig, f: Double): Double {
        if (!cfg.applyCurve || f <= 0.0) return 1.0
        val r = cfg.curve.responseAt(f).coerceIn(-cfg.maxCorrectionDb, cfg.maxCorrectionDb)
        return 10.0.pow(-r / 10.0)
    }

    private fun onHop() {
        val cfg = config
        val mc = meterCfg
        if (mc == null || mc.curve !== cfg.curve || mc.applyCurve != cfg.applyCurve || mc.maxCorrectionDb != cfg.maxCorrectionDb) {
            meter.design(cfg.curve, cfg.applyCurve, cfg.maxCorrectionDb)
        }
        meterCfg = cfg
        hopCount++

        if (resetStatsRequest) {
            resetStatsRequest = false
            meter.resetStats()
            peakMax = 0.0
        }

        lastRawZ = hopSq / HOP
        hopSq = 0.0
        lastPeak = blockPeak.toDouble()
        if (lastPeak > peakMax) peakMax = lastPeak
        if (blockPeak >= 0.999f) clipHold = (fs / HOP) * 2
        else if (clipHold > 0) clipHold--
        blockPeak = 0f

        // ---- captura de calibração por banda ----
        synchronized(capLock) {
            if (capMode == 2 && hopCount % 2 == 0L) {
                val cs = capSpec ?: PowerSpectrum(CAPTURE_N).also { capSpec = it }
                cs.computeFromRing(ring, ringPos)
                val bin = fs.toDouble() / CAPTURE_N
                val lo = capF / 2.0.pow(1.0 / 6.0)
                val hi = capF * 2.0.pow(1.0 / 6.0)
                var s = 0.0
                val k0 = kotlin.math.floor(lo / bin).toInt().coerceAtLeast(1)
                val k1 = kotlin.math.ceil(hi / bin).toInt().coerceAtMost(CAPTURE_N / 2)
                for (k in k0..k1) s += cs.power[k]
                capSum += s; capFrames++
            }
        }

        // ---- espectro de exibição (a cada 2 saltos) ----
        if (hopCount % 2 == 0L && !frozen) computeDisplay(cfg)

        publish(cfg)
    }

    private fun computeDisplay(cfg: AnalyzerConfig) {
        val n = cfg.displayN
        var ds = dispSpec
        var rebuilt = false
        if (ds == null || ds.n != n) {
            ds = PowerSpectrum(n); dispSpec = ds
            dispAvg = DoubleArray(ds.bins); dispPeak = DoubleArray(ds.bins) { -300.0 }
            tmpPow = DoubleArray(ds.bins)
            avgCount = 0; rebuilt = true
        }
        val binHz = fs.toDouble() / n
        val prev = dispCfg
        if (rebuilt || prev == null || prev.curve !== cfg.curve || prev.applyCurve != cfg.applyCurve ||
            prev.rtaWeighting != cfg.rtaWeighting || prev.maxCorrectionDb != cfg.maxCorrectionDb) {
            dispCorr = DoubleArray(ds.bins) { k ->
                val f = k * binHz
                if (k == 0) 0.0 else correctionFactor(cfg, f) * 10.0.pow(Weightings.db(cfg.rtaWeighting, f) / 10.0)
            }
            if (!rebuilt) { avgCount = 0; java.util.Arrays.fill(dispPeak, -300.0); java.util.Arrays.fill(bandPeakDb, -300.0) }
        }
        if (prev == null || prev.avgMode != cfg.avgMode) avgCount = 0
        if (bandsObj == null || bandsObj!!.bandsPerOctave != cfg.bandsPerOctave) {
            bandsObj = FractionalBands(cfg.bandsPerOctave)
            bandBuf = DoubleArray(bandsObj!!.centers.size)
            bandPeakDb = DoubleArray(bandsObj!!.centers.size) { -300.0 }
        }
        dispCfg = cfg
        if (resetPeakRequest) {
            resetPeakRequest = false
            java.util.Arrays.fill(dispPeak, -300.0)
            java.util.Arrays.fill(bandPeakDb, -300.0)
        }

        ds.computeFromRing(ring, ringPos)
        val frameDt = 2.0 * HOP / fs
        val alpha = when {
            cfg.avgMode == AvgMode.NONE -> 1.0
            cfg.avgMode == AvgMode.INF -> 1.0 / (avgCount + 1).toDouble()
            else -> max(1.0 - exp(-frameDt / cfg.avgMode.tau), 1.0 / (avgCount + 1).toDouble())
        }
        for (k in 0 until ds.bins) {
            val v = ds.power[k] * dispCorr[k]
            dispAvg[k] = if (avgCount == 0L) v else dispAvg[k] + (v - dispAvg[k]) * alpha
        }
        avgCount++
        val bo = bandsObj!!
        bo.aggregate(dispAvg, binHz, bandBuf)
        val off = cfg.offsetDb
        for (k in 0 until ds.bins) {
            val d = powToDb(dispAvg[k]) + off
            if (d > dispPeak[k]) dispPeak[k] = d
        }
        for (i in bandBuf.indices) {
            val d = powToDb(bandBuf[i]) + off
            if (d > bandPeakDb[i]) bandPeakDb[i] = d
        }
    }

    private fun publish(cfg: AnalyzerConfig) {
        val off = cfg.offsetDb
        synchronized(lock) {
            val s = snap
            s.valid = true
            s.fs = fs
            val m = meter
            for (w in 0 until 3) {
                s.fast[w] = powToDb(m.msFast[w]) + off
                s.slow[w] = powToDb(m.msSlow[w]) + off
                s.leq[w] = if (m.count > 0) powToDb(m.sumSq[w] / m.count) + off else -300.0
                s.maxF[w] = powToDb(m.maxF[w]) + off; s.minF[w] = powToDb(m.minF[w]) + off
                s.maxS[w] = powToDb(m.maxS[w]) + off; s.minS[w] = powToDb(m.minS[w]) + off
            }
            s.peakZ = 20.0 * log10(max(lastPeak, 1e-12)) + off
            s.peakZMax = 20.0 * log10(max(peakMax, 1e-12)) + off
            s.clipping = clipHold > 0
            s.elapsedSec = meter.count.toDouble() / fs
            s.rawDbfsZ = powToDb(lastRawZ)
            val ds = dispSpec
            if (ds != null && !frozen) {
                if (s.spectrum.size != ds.bins) { s.spectrum = DoubleArray(ds.bins); s.spectrumPeak = DoubleArray(ds.bins) }
                s.bins = ds.bins
                s.binHz = fs.toDouble() / ds.n
                var best = -1e9; var bestK = 0
                for (k in 0 until ds.bins) {
                    val d = powToDb(dispAvg[k]) + off
                    s.spectrum[k] = d
                    s.spectrumPeak[k] = dispPeak[k]
                    if (k > 0 && d > best && k * s.binHz >= 15.0) { best = d; bestK = k }
                }
                s.dominantHz = interpolatePeak(bestK, s.binHz)
                val bo = bandsObj
                if (bo != null) {
                    if (s.bands.size != bandBuf.size || s.bandCenters !== bo.centers) {
                        s.bands = DoubleArray(bandBuf.size); s.bandsPeak = DoubleArray(bandBuf.size)
                        s.bandCenters = bo.centers; s.bandLabels = bo.labels
                    }
                    for (i in bandBuf.indices) { s.bands[i] = powToDb(bandBuf[i]) + off; s.bandsPeak[i] = bandPeakDb[i] }
                }
            }
        }
    }

    /** Interpolação parabólica do pico (em dB) para estimar a frequência dominante. */
    private fun interpolatePeak(k: Int, binHz: Double): Double {
        if (k <= 0 || k >= dispAvg.size - 1) return k * binHz
        val a = powToDb(dispAvg[k - 1]); val b = powToDb(dispAvg[k]); val c = powToDb(dispAvg[k + 1])
        val den = a - 2 * b + c
        val d = if (abs(den) > 1e-12) 0.5 * (a - c) / den else 0.0
        return (k + d.coerceIn(-0.5, 0.5)) * binHz
    }

    /** Copia o estado atual para [dst] (thread da UI). */
    fun copySnapshot(dst: Snapshot) {
        synchronized(lock) {
            val s = snap
            dst.valid = s.valid; dst.fs = s.fs
            s.fast.copyInto(dst.fast); s.slow.copyInto(dst.slow); s.leq.copyInto(dst.leq)
            s.maxF.copyInto(dst.maxF); s.minF.copyInto(dst.minF); s.maxS.copyInto(dst.maxS); s.minS.copyInto(dst.minS)
            dst.peakZ = s.peakZ; dst.peakZMax = s.peakZMax; dst.clipping = s.clipping
            dst.elapsedSec = s.elapsedSec; dst.rawDbfsZ = s.rawDbfsZ
            dst.bins = s.bins; dst.binHz = s.binHz; dst.dominantHz = s.dominantHz
            if (dst.spectrum.size != s.spectrum.size) { dst.spectrum = DoubleArray(s.spectrum.size); dst.spectrumPeak = DoubleArray(s.spectrum.size) }
            s.spectrum.copyInto(dst.spectrum); s.spectrumPeak.copyInto(dst.spectrumPeak)
            if (dst.bands.size != s.bands.size) { dst.bands = DoubleArray(s.bands.size); dst.bandsPeak = DoubleArray(s.bands.size) }
            s.bands.copyInto(dst.bands); s.bandsPeak.copyInto(dst.bandsPeak)
            dst.bandCenters = s.bandCenters; dst.bandLabels = s.bandLabels
        }
    }

    // ---------- captura para calibração ----------

    /** Banda larga: média energética (Leq) com a ponderação dada, curva aplicada; resultado em dBFS. */
    fun startBroadbandCapture(w: Weighting) {
        synchronized(capLock) {
            capMode = 1; capSum = 0.0; capFrames = 0
            meter.capWeight = -1; meter.capSum = 0.0; meter.capCount = 0
            meter.capWeight = w.ordinal
        }
    }

    /** Banda de 1/3 de oitava em torno de f, SEM curva e SEM ponderação; resultado em dBFS. */
    fun startBandCapture(f: Double) {
        synchronized(capLock) { capMode = 2; capF = f; capSum = 0.0; capFrames = 0; meter.capWeight = -1 }
    }

    fun captureSeconds(): Double = synchronized(capLock) {
        when (capMode) {
            1 -> meter.capCount.toDouble() / fs
            2 -> capFrames * 2.0 * dt
            else -> 0.0
        }
    }

    fun captureResultDbfs(): Double = synchronized(capLock) {
        when (capMode) {
            1 -> if (meter.capCount == 0L) -300.0 else powToDb(meter.capSum / meter.capCount)
            2 -> if (capFrames == 0) -300.0 else powToDb(capSum / capFrames)
            else -> -300.0
        }
    }

    fun stopCapture() { synchronized(capLock) { capMode = 0; meter.capWeight = -1 } }
}
