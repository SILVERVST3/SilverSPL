package br.silver.spl.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complexa radix-2 iterativa, in-place, com tabelas pré-calculadas. */
class Fft(val n: Int) {
    private val levels: Int
    private val cosT: DoubleArray
    private val sinT: DoubleArray
    private val rev: IntArray

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "n deve ser potência de 2" }
        var l = 0
        while ((1 shl l) < n) l++
        levels = l
        cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
        sinT = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
        rev = IntArray(n) { i ->
            var x = i
            var r = 0
            for (b in 0 until levels) {
                r = (r shl 1) or (x and 1); x = x ushr 1
            }
            r
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * Espectro de potência unilateral normalizado (janela Hann), de modo que
 * a soma de todos os bins = valor quadrático médio (MS) do sinal.
 */
class PowerSpectrum(val n: Int) {
    private val fft = Fft(n)
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    private val sumW2 = window.sumOf { it * it }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    val bins = n / 2 + 1
    val power = DoubleArray(bins)

    /** Lê os últimos n amostras do anel [ring] terminando em [endPos] (exclusivo). */
    fun computeFromRing(ring: FloatArray, endPos: Int) {
        val size = ring.size
        var start = endPos - n
        while (start < 0) start += size
        var mean = 0.0
        for (i in 0 until n) mean += ring[(start + i) % size]
        mean /= n
        for (i in 0 until n) {
            re[i] = (ring[(start + i) % size] - mean) * window[i]
            im[i] = 0.0
        }
        finish()
    }

    fun computeFrom(x: DoubleArray) {
        var mean = 0.0
        for (i in 0 until n) mean += x[i]
        mean /= n
        for (i in 0 until n) { re[i] = (x[i] - mean) * window[i]; im[i] = 0.0 }
        finish()
    }

    private fun finish() {
        fft.transform(re, im)
        val norm = 1.0 / (n * sumW2)
        power[0] = 0.0 // DC removido
        for (k in 1 until n / 2) power[k] = 2.0 * (re[k] * re[k] + im[k] * im[k]) * norm
        power[n / 2] = (re[n / 2] * re[n / 2] + im[n / 2] * im[n / 2]) * norm
    }
}
