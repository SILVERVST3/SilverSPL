package br.silver.spl.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

enum class Weighting(val label: String) { A("A"), C("C"), Z("Z") }

object Weightings {
    // IEC 61672-1:2013, expressões analíticas
    private const val F1 = 20.598997
    private const val F2 = 107.65265
    private const val F3 = 737.86223
    private const val F4 = 12194.217

    fun aDb(f: Double): Double {
        if (f <= 0.0) return -200.0
        val f2 = f * f
        val ra = (F4 * F4 * f2 * f2) /
                ((f2 + F1 * F1) * sqrt((f2 + F2 * F2) * (f2 + F3 * F3)) * (f2 + F4 * F4))
        return 20.0 * log10(ra) + 2.0
    }

    fun cDb(f: Double): Double {
        if (f <= 0.0) return -200.0
        val f2 = f * f
        val rc = (F4 * F4 * f2) / ((f2 + F1 * F1) * (f2 + F4 * F4))
        return 20.0 * log10(rc) + 0.0619
    }

    fun db(w: Weighting, f: Double): Double = when (w) {
        Weighting.A -> aDb(f)
        Weighting.C -> cDb(f)
        Weighting.Z -> 0.0
    }
}

/** Bandas de fração de oitava (base 10, IEC 61260). */
class FractionalBands(val bandsPerOctave: Int, fMin: Double = 18.0, fMax: Double = 21000.0) {
    val centers: DoubleArray
    val lower: DoubleArray
    val upper: DoubleArray
    val labels: Array<String>

    init {
        val c = ArrayList<Double>()
        // fm = 1000 * 10^(3x / (10 b))
        val b = bandsPerOctave.toDouble()
        var x = -60
        while (x < 60) {
            val fm = 1000.0 * 10.0.pow(3.0 * x / (10.0 * b))
            if (fm in fMin..fMax) c.add(fm)
            x++
        }
        centers = c.toDoubleArray()
        val g = 10.0.pow(3.0 / (20.0 * b))
        lower = DoubleArray(centers.size) { centers[it] / g }
        upper = DoubleArray(centers.size) { centers[it] * g }
        labels = Array(centers.size) { nominalLabel(centers[it]) }
    }

    companion object {
        private val NOMINAL = doubleArrayOf(
            10.0, 12.5, 16.0, 20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0,
            250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0,
            3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
        )

        fun nominal(f: Double): Double {
            var best = f
            var bestErr = Double.MAX_VALUE
            for (n in NOMINAL) {
                val e = kotlin.math.abs(log10(n / f))
                if (e < bestErr) { bestErr = e; best = n }
            }
            return if (bestErr < 0.02) best else f
        }

        fun nominalLabel(f: Double): String = formatFreq(nominal(f))

        private fun trimNum(v: Double, dec: Int): String {
            var s = String.format(java.util.Locale.US, "%.${dec}f", v)
            if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
            return s
        }

        fun formatFreq(f: Double): String = when {
            f >= 10000 -> trimNum(f / 1000, 1) + "k"
            f >= 1000 -> trimNum(f / 1000, 2) + "k"
            f >= 100 -> trimNum(f, 0)
            else -> trimNum(f, 1)
        }
    }

    /**
     * Soma a potência dos bins dentro de cada banda, levando em conta a
     * fração de sobreposição de cada bin com a banda.
     */
    fun aggregate(power: DoubleArray, binHz: Double, out: DoubleArray) {
        val nb = power.size
        for (i in centers.indices) {
            val lo = lower[i]
            val hi = upper[i]
            var kStart = kotlin.math.floor(lo / binHz - 0.5).toInt().coerceAtLeast(1)
            val kEnd = kotlin.math.ceil(hi / binHz + 0.5).toInt().coerceAtMost(nb - 1)
            var sum = 0.0
            while (kStart <= kEnd) {
                val bLo = (kStart - 0.5) * binHz
                val bHi = (kStart + 0.5) * binHz
                val ov = kotlin.math.min(hi, bHi) - kotlin.math.max(lo, bLo)
                if (ov > 0) sum += power[kStart] * (ov / binHz)
                kStart++
            }
            out[i] = sum
        }
    }
}

fun powToDb(p: Double): Double = if (p > 1e-30) 10.0 * log10(p) else -300.0
