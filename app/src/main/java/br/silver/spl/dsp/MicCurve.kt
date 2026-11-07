package br.silver.spl.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Curva de resposta do microfone (convenção dos arquivos de calibração REW/miniDSP/Dayton):
 * valor positivo = o microfone lê ALTO nessa frequência, então o app SUBTRAI esse valor.
 */
class MicCurve(
    val id: String,
    val name: String,
    points: List<Pair<Double, Double>>,
    val note: String = ""
) {
    val freqs: DoubleArray
    val dbs: DoubleArray

    init {
        val clean = points.filter { it.first > 0.0 && it.first.isFinite() && it.second.isFinite() }
            .sortedBy { it.first }
            .distinctBy { it.first }
        freqs = DoubleArray(clean.size) { clean[it].first }
        dbs = DoubleArray(clean.size) { clean[it].second }
    }

    val isFlat: Boolean get() = dbs.all { kotlin.math.abs(it) < 1e-9 }
    val size: Int get() = freqs.size

    /** Resposta em dB na frequência f, interpolação linear em log(f); constante fora da faixa. */
    fun responseAt(f: Double): Double {
        if (freqs.isEmpty()) return 0.0
        if (f <= freqs[0]) return dbs[0]
        if (f >= freqs[freqs.size - 1]) return dbs[dbs.size - 1]
        var lo = 0
        var hi = freqs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (freqs[mid] <= f) lo = mid else hi = mid
        }
        val x0 = log10(freqs[lo]); val x1 = log10(freqs[hi])
        val t = (log10(f) - x0) / (x1 - x0)
        return dbs[lo] + t * (dbs[hi] - dbs[lo])
    }

    fun points(): List<Pair<Double, Double>> = freqs.indices.map { freqs[it] to dbs[it] }

    /** Retorna uma cópia normalizada (0 dB em 1 kHz) e o valor removido. */
    fun normalizedAt1k(): Pair<MicCurve, Double> {
        val r = responseAt(1000.0)
        return MicCurve(id, name, points().map { it.first to it.second - r }, note) to r
    }

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("* SilverSPL - curva de resposta do microfone\n")
        sb.append("* ").append(name.replace('\n', ' ')).append('\n')
        if (note.isNotBlank()) sb.append("* ").append(note.replace('\n', ' ')).append('\n')
        sb.append("* Formato: frequencia(Hz) resposta(dB) - positivo = mic le alto\n")
        for (i in freqs.indices) {
            sb.append(String.format(java.util.Locale.US, "%.3f\t%.3f\n", freqs[i], dbs[i]))
        }
        return sb.toString()
    }

    companion object {
        const val ID_FLAT = "flat"
        const val ID_MEMS_TYP = "mems_typ"
        const val ID_MEMS_STRONG = "mems_strong"
        const val ID_VOICE = "voice_cdd"
        const val ID_DEVICE = "device"
        const val ID_FILE = "file"
        const val ID_USER = "user"

        val flat = MicCurve(ID_FLAT, "Plana (sem correção)", listOf(1000.0 to 0.0))

        /**
         * Modelo físico de um MEMS embutido em smartphone:
         *  - roll-off de 1ª ordem nos graves (furo de ventilação/câmara traseira);
         *  - ressonância de Helmholtz de 2ª ordem do canal acústico nos agudos;
         *  - leve reforço de difração do corpo do aparelho acima de ~4 kHz.
         */
        fun memsModel(fLow: Double, fRes: Double, q: Double, bodyBoostDb: Double): List<Pair<Double, Double>> {
            val pts = ArrayList<Pair<Double, Double>>()
            var x = -40 // 1/6 oitava de 10 Hz a ~22 kHz
            while (true) {
                val f = 1000.0 * 10.0.pow(x * 0.05)
                if (f > 22000) break
                if (f >= 10) {
                    val hp = -10.0 * log10(1.0 + (fLow / f).pow(2))
                    val r = f / fRes
                    val re = 1.0 - r * r
                    val im = r / q
                    val res = -20.0 * log10(sqrt(re * re + im * im))
                    val body = bodyBoostDb / (1.0 + (4000.0 / f).pow(2))
                    pts.add(f to hp + res + body)
                }
                x++
            }
            // normaliza em 1 kHz
            val c = MicCurve("tmp", "tmp", pts)
            val r1k = c.responseAt(1000.0)
            return pts.map { it.first to it.second - r1k }
        }

        val memsTypical = MicCurve(
            ID_MEMS_TYP, "Smartphone MEMS típico (estimada)",
            memsModel(fLow = 50.0, fRes = 12500.0, q = 1.6, bodyBoostDb = 1.5),
            "Modelo genérico: graves -3 dB em ~50 Hz, ressonância ~12,5 kHz (+4 dB). Não é medição do A55."
        )

        val memsStrong = MicCurve(
            ID_MEMS_STRONG, "Smartphone MEMS c/ ressonância forte (estimada)",
            memsModel(fLow = 80.0, fRes = 9000.0, q = 2.5, bodyBoostDb = 2.0),
            "Modelo genérico: graves -3 dB em ~80 Hz, ressonância ~9 kHz (+8 dB)."
        )

        /** Leitura de arquivos texto: "freq dB [fase]" por linha, separadores espaço/tab/;/, */
        fun parse(text: String, id: String, name: String): MicCurve {
            val pts = ArrayList<Pair<Double, Double>>()
            var sens: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("*") || line.startsWith("#") || line.startsWith("\"")) {
                    if (line.contains("Sens", ignoreCase = true)) sens = line.trimStart('*', '#', '"', ' ')
                    continue
                }
                if (line.contains("Sens Factor", ignoreCase = true)) { sens = line; continue }
                val parts = line.split(Regex("[\\s;,]+")).filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val f = parts[0].toDoubleOrNull() ?: continue
                val d = parts[1].toDoubleOrNull() ?: continue
                pts.add(f to d)
            }
            val note = if (sens != null) "Arquivo informa: $sens" else ""
            return MicCurve(id, name, pts, note)
        }
    }
}
