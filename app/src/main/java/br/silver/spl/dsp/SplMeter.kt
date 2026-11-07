package br.silver.spl.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** Biquad em forma direta II transposta (coeficientes reais, a0 = 1). */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /** |H(e^jw)| */
    fun mag(w: Double): Double {
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return hypot(nr, ni) / hypot(dr, di)
    }

    companion object {
        fun fromRoots(z1: Double, z2: Double, p1: Double, p2: Double) =
            Biquad(1.0, -(z1 + z2), z1 * z2, -(p1 + p2), p1 * p2)
    }
}

/** Filtros de ponderação A e C por transformação bilinear, normalizados em 1 kHz. */
class WeightingIir(val w: Weighting, val fs: Int) {
    private val sections: List<Biquad>
    private val gain: Double

    init {
        fun bz(fp: Double): Double { // polo analógico em -2*pi*fp -> plano z
            val p = -2.0 * PI * fp
            val k = p / (2.0 * fs)
            return (1 + k) / (1 - k)
        }
        val p1 = bz(20.598997); val p2 = bz(107.65265); val p3 = bz(737.86223); val p4 = bz(12194.217)
        sections = when (w) {
            Weighting.A -> listOf(
                Biquad.fromRoots(1.0, 1.0, p1, p1),
                Biquad.fromRoots(1.0, 1.0, p4, p4),
                Biquad.fromRoots(-1.0, -1.0, p2, p3)
            )
            Weighting.C -> listOf(
                Biquad.fromRoots(1.0, 1.0, p1, p1),
                Biquad.fromRoots(-1.0, -1.0, p4, p4)
            )
            Weighting.Z -> emptyList()
        }
        val w1k = 2.0 * PI * 1000.0 / fs
        val raw = sections.fold(1.0) { acc, s -> acc * s.mag(w1k) }
        val target = Math.pow(10.0, Weightings.db(w, 1000.0) / 20.0)
        gain = if (sections.isEmpty()) 1.0 else target / raw
    }

    fun process(x: Double): Double {
        var y = x * gain
        for (s in sections) y = s.process(y)
        return y
    }

    /** Resposta digital real em dB (para compensar o erro da bilinear nos agudos). */
    fun digitalDb(f: Double): Double {
        if (sections.isEmpty()) return 0.0
        val wv = 2.0 * PI * f / fs
        val m = sections.fold(gain) { acc, s -> acc * s.mag(wv) }
        return 20.0 * kotlin.math.log10(kotlin.math.max(m, 1e-15))
    }
}

/**
 * Medidor de nível sonoro no domínio do tempo:
 * entrada -> FIR de fase linear (correção da curva do mic [+ compensação da bilinear]) -> IIR A/C
 * -> quadrado -> média exponencial Fast (125 ms) / Slow (1 s) + Leq.
 * O FIR é aplicado por overlap-save (FFT 8192, filtro de 4097 taps, bloco de 4096).
 */
class SplMeter(val fs: Int) {
    companion object {
        const val NFFT = 8192
        const val TAPS = 4097
        const val BLOCK = NFFT - TAPS + 1 // 4096
    }

    private val fft = Fft(NFFT)
    private val iirs = arrayOf(WeightingIir(Weighting.A, fs), WeightingIir(Weighting.C, fs), WeightingIir(Weighting.Z, fs))
    private val firRe = Array(3) { DoubleArray(NFFT) }
    private val firIm = Array(3) { DoubleArray(NFFT) }
    private val inBuf = DoubleArray(NFFT)
    private var fill = TAPS - 1
    private val xr = DoubleArray(NFFT); private val xi = DoubleArray(NFFT)
    private val yr = DoubleArray(NFFT); private val yi = DoubleArray(NFFT)

    private val aF = 1.0 - exp(-1.0 / (fs * 0.125))
    private val aS = 1.0 - exp(-1.0 / (fs * 1.0))

    val msFast = DoubleArray(3)
    val msSlow = DoubleArray(3)
    val sumSq = DoubleArray(3)
    var count = 0L
    val maxF = DoubleArray(3); val minF = DoubleArray(3)
    val maxS = DoubleArray(3); val minS = DoubleArray(3)
    private var warm = 0L
    private var initialized = false
    private var settle = 0L // amostras desde o início (o Slow precisa de ~1 s para acomodar)

    // captura de calibração em banda larga
    @Volatile var capWeight = -1
    @Volatile var capSum = 0.0
    @Volatile var capCount = 0L

    init { resetStats(); design(MicCurve.flat, false, 20.0) }

    fun resetStats() {
        for (w in 0 until 3) {
            sumSq[w] = 0.0
            maxF[w] = 0.0; maxS[w] = 0.0; minF[w] = Double.MAX_VALUE; minS[w] = Double.MAX_VALUE
        }
        count = 0
        warm = 0
    }

    /** Projeta os 3 FIRs (A, C, Z). */
    fun design(curve: MicCurve, apply: Boolean, maxCorr: Double) {
        val m = NFFT
        val half = (TAPS - 1) / 2
        val re = DoubleArray(m); val im = DoubleArray(m)
        for (w in 0 until 3) {
            for (k in 0..m / 2) {
                val f = k * fs.toDouble() / m
                var db = 0.0
                if (apply && f > 0) db -= curve.responseAt(f).coerceIn(-maxCorr, maxCorr)
                if (w < 2 && f > 0) {
                    val wt = if (w == 0) Weighting.A else Weighting.C
                    val err = (Weightings.db(wt, f) - iirs[w].digitalDb(f)).coerceIn(-3.0, 12.0)
                    // compensa só acima de 2 kHz (abaixo a bilinear já é exata)
                    val blend = ((f - 2000.0) / 2000.0).coerceIn(0.0, 1.0)
                    db += err * blend
                }
                val mag = Math.pow(10.0, db / 20.0)
                re[k] = mag; im[k] = 0.0
                if (k in 1 until m / 2) { re[m - k] = mag; im[m - k] = 0.0 }
            }
            // espectro real e par -> resposta ao impulso real e par
            fft.transform(re, im)
            val h = DoubleArray(m)
            for (n in -half..half) {
                val win = 0.5 + 0.5 * cos(PI * n / (half + 1))
                h[n + half] = re[(n + m) % m] / m * win
            }
            for (i in 0 until m) { firRe[w][i] = h[i]; firIm[w][i] = 0.0 }
            fft.transform(firRe[w], firIm[w])
        }
    }

    fun push(x: Float) {
        inBuf[fill++] = x.toDouble()
        if (fill == NFFT) runBlock()
    }

    private fun runBlock() {
        for (i in 0 until NFFT) { xr[i] = inBuf[i]; xi[i] = 0.0 }
        fft.transform(xr, xi)
        val outs = Array(3) { DoubleArray(BLOCK) }
        for (w in 0 until 3) {
            val hr = firRe[w]; val hi = firIm[w]
            for (k in 0 until NFFT) {
                val r = xr[k] * hr[k] - xi[k] * hi[k]
                val ii = xr[k] * hi[k] + xi[k] * hr[k]
                yr[k] = r; yi[k] = -ii // conj para IFFT
            }
            fft.transform(yr, yi)
            val o = outs[w]
            for (i in 0 until BLOCK) o[i] = yr[TAPS - 1 + i] / NFFT
        }
        // desloca o histórico
        System.arraycopy(inBuf, BLOCK, inBuf, 0, TAPS - 1)
        fill = TAPS - 1

        for (w in 0 until 3) {
            val o = outs[w]
            for (i in 0 until BLOCK) { val y = iirs[w].process(o[i]); o[i] = y * y }
        }
        if (!initialized) {
            // inicia as médias exponenciais com o MS do primeiro bloco (evita rampa a partir de zero)
            for (w in 0 until 3) { val mean = outs[w].average(); msFast[w] = mean; msSlow[w] = mean }
        }
        for (i in 0 until BLOCK) {
            for (w in 0 until 3) {
                val sq = outs[w][i]
                msFast[w] += (sq - msFast[w]) * aF
                msSlow[w] += (sq - msSlow[w]) * aS
                sumSq[w] += sq
                if (w == capWeight) { capSum += sq }
            }
            initialized = true
            if (settle <= fs) settle++
            if (capWeight >= 0) capCount++
            count++
            warm++
            // máx/mín a cada 256 amostras, após 0,3 s de acomodação
            if ((warm and 255L) == 0L && warm > fs * 3L / 10L && settle > fs) {
                for (w in 0 until 3) {
                    val f = msFast[w]; val s = msSlow[w]
                    if (f > maxF[w]) maxF[w] = f
                    if (f < minF[w]) minF[w] = f
                    if (s > maxS[w]) maxS[w] = s
                    if (s < minS[w]) minS[w] = s
                }
            }
        }
    }
}
