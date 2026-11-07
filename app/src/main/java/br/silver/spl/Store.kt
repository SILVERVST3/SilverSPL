package br.silver.spl

import android.content.Context
import br.silver.spl.audio.InputSource
import br.silver.spl.dsp.AvgMode
import br.silver.spl.dsp.MicCurve
import br.silver.spl.dsp.Weighting

/** Preferências e curvas salvas (SharedPreferences). */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("silverspl", Context.MODE_PRIVATE)

    var source: InputSource
        get() = runCatching { InputSource.valueOf(sp.getString("source", InputSource.UNPROCESSED.name)!!) }
            .getOrDefault(InputSource.UNPROCESSED)
        set(v) = sp.edit().putString("source", v.name).apply()

    var inputDeviceKey: String
        get() = sp.getString("inputDev", "builtin")!!
        set(v) = sp.edit().putString("inputDev", v).apply()

    private fun offKey(source: InputSource, dev: String) = "off_${source.name}_$dev"

    fun offset(source: InputSource, dev: String): Double =
        sp.getFloat(offKey(source, dev), source.nominalOffset.toFloat()).toDouble()

    fun setOffset(source: InputSource, dev: String, v: Double) =
        sp.edit().putFloat(offKey(source, dev), v.toFloat()).apply()

    fun hasCustomOffset(source: InputSource, dev: String) = sp.contains(offKey(source, dev))

    fun resetOffset(source: InputSource, dev: String) = sp.edit().remove(offKey(source, dev)).apply()

    var curveId: String
        get() = sp.getString("curveId", MicCurve.ID_FLAT)!!
        set(v) = sp.edit().putString("curveId", v).apply()

    var applyCurve: Boolean
        get() = sp.getBoolean("applyCurve", true)
        set(v) = sp.edit().putBoolean("applyCurve", v).apply()

    var maxCorrection: Double
        get() = sp.getFloat("maxCorr", 20f).toDouble()
        set(v) = sp.edit().putFloat("maxCorr", v.toFloat()).apply()

    var displayN: Int
        get() = sp.getInt("displayN", 8192)
        set(v) = sp.edit().putInt("displayN", v).apply()

    var bandsPerOctave: Int // 0 = FFT
        get() = sp.getInt("bpo", 3)
        set(v) = sp.edit().putInt("bpo", v).apply()

    var rtaWeighting: Weighting
        get() = runCatching { Weighting.valueOf(sp.getString("rtaW", "Z")!!) }.getOrDefault(Weighting.Z)
        set(v) = sp.edit().putString("rtaW", v.name).apply()

    var meterWeighting: Weighting
        get() = runCatching { Weighting.valueOf(sp.getString("meterW", "A")!!) }.getOrDefault(Weighting.A)
        set(v) = sp.edit().putString("meterW", v.name).apply()

    var slow: Boolean
        get() = sp.getBoolean("slow", false)
        set(v) = sp.edit().putBoolean("slow", v).apply()

    var avgMode: AvgMode
        get() = runCatching { AvgMode.valueOf(sp.getString("avg", "FAST")!!) }.getOrDefault(AvgMode.FAST)
        set(v) = sp.edit().putString("avg", v.name).apply()

    var peakHold: Boolean
        get() = sp.getBoolean("peakHold", true)
        set(v) = sp.edit().putBoolean("peakHold", v).apply()

    var yMin: Int
        get() = sp.getInt("yMin", 0)
        set(v) = sp.edit().putInt("yMin", v).apply()

    var yMax: Int
        get() = sp.getInt("yMax", 110)
        set(v) = sp.edit().putInt("yMax", v).apply()

    // ---- curvas salvas ----
    fun saveCurve(c: MicCurve) {
        sp.edit().putString("curve_${c.id}", c.toText()).putString("curveName_${c.id}", c.name)
            .putString("curveNote_${c.id}", c.note).apply()
    }

    fun loadCurve(id: String): MicCurve? {
        val txt = sp.getString("curve_$id", null) ?: return null
        val name = sp.getString("curveName_$id", id) ?: id
        val note = sp.getString("curveNote_$id", "") ?: ""
        val c = MicCurve.parse(txt, id, name)
        if (c.size == 0) return null
        return MicCurve(id, name, c.points(), note)
    }

    fun deleteCurve(id: String) {
        sp.edit().remove("curve_$id").remove("curveName_$id").remove("curveNote_$id").apply()
    }

    /** Pontos em andamento do assistente de medição (freq -> resposta dB). */
    var wizardPoints: List<Pair<Double, Double>>
        get() {
            val s = sp.getString("wizPts", "") ?: ""
            return s.split(';').mapNotNull {
                val p = it.split(':'); if (p.size != 2) null
                else { val f = p[0].toDoubleOrNull(); val d = p[1].toDoubleOrNull(); if (f != null && d != null) f to d else null }
            }
        }
        set(v) = sp.edit().putString("wizPts", v.joinToString(";") { "${it.first}:${it.second}" }).apply()
}
