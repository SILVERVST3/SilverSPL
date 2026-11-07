package br.silver.spl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import br.silver.spl.dsp.FractionalBands
import br.silver.spl.dsp.MicCurve
import br.silver.spl.dsp.Snapshot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object Pal {
    val BG = Color.rgb(0x0E, 0x10, 0x13)
    val CARD = Color.rgb(0x1A, 0x1D, 0x22)
    val CARD2 = Color.rgb(0x22, 0x26, 0x2C)
    val STROKE = Color.rgb(0x3A, 0x40, 0x48)
    val SILVER_HI = Color.rgb(0xEC, 0xEF, 0xF2)
    val SILVER = Color.rgb(0xC3, 0xC9, 0xD0)
    val SILVER_LO = Color.rgb(0x7D, 0x86, 0x91)
    val TEXT = Color.rgb(0xE4, 0xE7, 0xEB)
    val TEXT_DIM = Color.rgb(0x8E, 0x96, 0xA0)
    val GRID = Color.rgb(0x2A, 0x2F, 0x36)
    val ACCENT = Color.rgb(0x8F, 0xD3, 0xFF)
    val PEAK = Color.rgb(0xFF, 0xC8, 0x6B)
    val RED = Color.rgb(0xFF, 0x5A, 0x5A)
    val GREEN = Color.rgb(0x7E, 0xE0, 0x9A)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Eixo logarítmico de frequência. */
private class LogAxis(var fMin: Double, var fMax: Double) {
    fun x(f: Double, left: Float, width: Float): Float =
        left + (width * (log10(f / fMin) / log10(fMax / fMin))).toFloat()

    fun f(x: Float, left: Float, width: Float): Double =
        fMin * (fMax / fMin).pow(((x - left) / width).toDouble())
}

/** Analisador gráfico: barras de fração de oitava e/ou linha FFT, pico, cursor. */
class SpectrumView(ctx: Context) : View(ctx) {
    var snap: Snapshot? = null
    var bandMode = true
    var showLineInBands = true
    var showPeak = true
    var yMin = 0f
    var yMax = 110f
    var unitLabel = "dB SPL"

    private val axis = LogAxis(20.0, 20000.0)
    private val gridP = Paint().apply { color = Pal.GRID; strokeWidth = ctx.dp(1f) }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT_DIM; textSize = ctx.dp(10f) }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PEAK; strokeWidth = ctx.dp(2f) }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.3f)
    }
    private val lineDimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0x8F, 0xD3, 0xFF); style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f)
    }
    private val peakLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0xFF, 0xC8, 0x6B); style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f)
    }
    private val cursorP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.SILVER_HI; strokeWidth = ctx.dp(1f) }
    private val cursorTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.SILVER_HI; textSize = ctx.dp(13f); typeface = Typeface.DEFAULT_BOLD
    }
    private val cursorBg = Paint().apply { color = Color.argb(210, 0x10, 0x12, 0x15) }
    private val path = Path()
    private val path2 = Path()
    private var cursorX = -1f
    private var colMax = FloatArray(0)
    private var colMaxPk = FloatArray(0)
    private var shaderH = -1

    private val padL get() = context.dp(34f)
    private val padB get() = context.dp(18f)
    private val padT get() = context.dp(6f)
    private val padR get() = context.dp(6f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { cursorX = e.x; parent?.requestDisallowInterceptTouchEvent(true); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { /* mantém o cursor */ }
        }
        return true
    }

    fun clearCursor() { cursorX = -1f; invalidate() }

    private fun yOf(db: Double, top: Float, h: Float): Float {
        val t = ((db - yMin) / (yMax - yMin)).toFloat().coerceIn(-0.05f, 1.05f)
        return top + h * (1f - t)
    }

    override fun onDraw(c: Canvas) {
        val s = snap
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Pal.CARD)
        val left = padL; val top = padT
        val pw = w - padL - padR; val ph = h - padT - padB
        if (pw <= 10 || ph <= 10) return
        axis.fMax = if (s != null && s.fs > 0) min(20000.0, s.fs / 2.0) else 20000.0

        // grade
        val fGrid = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        labelP.textAlign = Paint.Align.CENTER
        for (f in fGrid) {
            if (f > axis.fMax) continue
            val x = axis.x(f, left, pw)
            c.drawLine(x, top, x, top + ph, gridP)
            c.drawText(FractionalBands.formatFreq(f), x, h - context.dp(5f), labelP)
        }
        labelP.textAlign = Paint.Align.RIGHT
        val step = if (yMax - yMin > 80) 10 else 5
        var d = (kotlin.math.ceil(yMin / step) * step).toInt()
        while (d <= yMax) {
            val y = yOf(d.toDouble(), top, ph)
            c.drawLine(left, y, left + pw, y, gridP)
            c.drawText(d.toString(), left - context.dp(4f), y + context.dp(3.5f), labelP)
            d += step
        }
        if (s == null || !s.valid) return

        if (shaderH != h.toInt()) {
            barP.shader = LinearGradient(0f, top, 0f, top + ph,
                intArrayOf(Pal.SILVER_HI, Pal.SILVER, Pal.SILVER_LO), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            shaderH = h.toInt()
        }

        c.save()
        c.clipRect(left, top, left + pw, top + ph)
        // linha FFT
        if (s.bins > 2 && (!bandMode || showLineInBands)) {
            drawFftLine(c, s, left, top, pw, ph, if (bandMode) lineDimP else lineP)
        }
        // barras
        if (bandMode && s.bands.isNotEmpty()) {
            val g = 10.0.pow(3.0 / (20.0 * guessBpo(s)))
            val gap = context.dp(1f)
            val r = RectF()
            for (i in s.bands.indices) {
                val fc = s.bandCenters[i]
                if (fc > axis.fMax * 1.05) continue
                val x0 = axis.x(fc / g, left, pw) + gap
                val x1 = axis.x(fc * g, left, pw) - gap
                val y = yOf(s.bands[i], top, ph)
                r.set(x0, y, max(x1, x0 + 1f), top + ph)
                c.drawRect(r, barP)
                if (showPeak) {
                    val yp = yOf(s.bandsPeak[i], top, ph)
                    c.drawLine(x0, yp, max(x1, x0 + 1f), yp, peakP)
                }
            }
        }
        c.restore()

        // cursor
        if (cursorX in left..(left + pw)) {
            val f = axis.f(cursorX, left, pw)
            c.drawLine(cursorX, top, cursorX, top + ph, cursorP)
            val lvl: Double
            val fTxt: String
            if (bandMode && s.bands.isNotEmpty()) {
                var bi = 0; var best = Double.MAX_VALUE
                for (i in s.bandCenters.indices) {
                    val e = kotlin.math.abs(log10(s.bandCenters[i] / f)); if (e < best) { best = e; bi = i }
                }
                lvl = s.bands[bi]
                fTxt = s.bandLabels[bi] + " Hz"
            } else {
                val k = (f / s.binHz).toInt().coerceIn(1, s.bins - 1)
                lvl = s.spectrum[k]
                fTxt = if (f >= 1000) String.format("%.2f kHz", f / 1000) else String.format("%.1f Hz", f)
            }
            val txt = "$fTxt  ${fmt1(lvl)} $unitLabel"
            val tw = cursorTxt.measureText(txt)
            var tx = cursorX + context.dp(6f)
            if (tx + tw > w - padR) tx = cursorX - tw - context.dp(6f)
            val ty = top + context.dp(16f)
            c.drawRect(tx - context.dp(4f), ty - context.dp(13f), tx + tw + context.dp(4f), ty + context.dp(4f), cursorBg)
            c.drawText(txt, tx, ty, cursorTxt)
        }
    }

    private fun guessBpo(s: Snapshot): Int {
        if (s.bandCenters.size < 2) return 3
        val ratio = s.bandCenters[1] / s.bandCenters[0]
        return Math.round(log10(2.0) / log10(ratio)).toInt().coerceAtLeast(1)
    }

    private fun drawFftLine(c: Canvas, s: Snapshot, left: Float, top: Float, pw: Float, ph: Float, p: Paint) {
        val cols = pw.toInt() + 1
        if (colMax.size != cols) { colMax = FloatArray(cols); colMaxPk = FloatArray(cols) }
        java.util.Arrays.fill(colMax, Float.NaN); java.util.Arrays.fill(colMaxPk, Float.NaN)
        val fLo = axis.fMin * 0.98
        for (k in 1 until s.bins) {
            val f = k * s.binHz
            if (f < fLo) continue
            if (f > axis.fMax) break
            val ci = (axis.x(f, 0f, pw)).toInt().coerceIn(0, cols - 1)
            val v = s.spectrum[k].toFloat()
            if (colMax[ci].isNaN() || v > colMax[ci]) colMax[ci] = v
            val vp = s.spectrumPeak[k].toFloat()
            if (colMaxPk[ci].isNaN() || vp > colMaxPk[ci]) colMaxPk[ci] = vp
        }
        path.reset(); path2.reset()
        var started = false; var started2 = false
        for (i in 0 until cols) {
            val v = colMax[i]
            if (v.isNaN()) continue
            val x = left + i
            val y = yOf(v.toDouble(), top, ph)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            if (showPeak && !bandMode) {
                val yp = yOf(colMaxPk[i].toDouble(), top, ph)
                if (!started2) { path2.moveTo(x, yp); started2 = true } else path2.lineTo(x, yp)
            }
        }
        if (started2) c.drawPath(path2, peakLineP)
        c.drawPath(path, p)
    }
}

/** Barra horizontal de nível (20–130 dB) com marcador de máximo. */
class LevelBar(ctx: Context) : View(ctx) {
    var level = -300.0
    var maxLevel = -300.0
    var clip = false
    private val lo = 20.0; private val hi = 130.0
    private val bg = Paint().apply { color = Pal.CARD2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint().apply { color = Pal.PEAK; strokeWidth = ctx.dp(2f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT_DIM; textSize = ctx.dp(9f); textAlign = Paint.Align.CENTER }
    private var sw = -1

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val barH = h - context.dp(11f)
        if (sw != width) {
            fill.shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Pal.SILVER_LO, Pal.SILVER, Pal.SILVER_HI, Pal.PEAK, Pal.RED),
                floatArrayOf(0f, 0.45f, 0.7f, 0.85f, 1f), Shader.TileMode.CLAMP)
            sw = width
        }
        c.drawRect(0f, 0f, w, barH, bg)
        val t = ((level - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        c.drawRect(0f, 0f, w * t, barH, fill)
        val tm = ((maxLevel - lo) / (hi - lo)).toFloat()
        if (tm in 0f..1f) c.drawLine(w * tm, 0f, w * tm, barH, mark)
        var d = 30
        while (d <= 120) {
            val x = (w * (d - lo) / (hi - lo)).toFloat()
            c.drawText(d.toString(), x, h - context.dp(1f), tick)
            d += 10
        }
        if (clip) {
            val p = Paint().apply { color = Pal.RED }
            c.drawRect(w - context.dp(8f), 0f, w, barH, p)
        }
    }
}

/** Gráfico da curva do microfone (resposta) e da correção aplicada. */
class CurveView(ctx: Context) : View(ctx) {
    var curve: MicCurve? = null
    var maxCorr = 20.0
    private val axis = LogAxis(20.0, 20000.0)
    private val gridP = Paint().apply { color = Pal.GRID; strokeWidth = ctx.dp(1f) }
    private val zeroP = Paint().apply { color = Pal.STROKE; strokeWidth = ctx.dp(1.5f) }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT_DIM; textSize = ctx.dp(9.5f) }
    private val respP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.SILVER_HI; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f) }
    private val corrP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.ACCENT; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(5f), ctx.dp(4f)), 0f)
    }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PEAK }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.CARD2)
        val w = width.toFloat(); val h = height.toFloat()
        val left = context.dp(28f); val top = context.dp(6f)
        val pw = w - left - context.dp(6f); val ph = h - top - context.dp(16f)
        val cv = curve
        var range = 12.0
        if (cv != null) for (v in cv.dbs) range = max(range, kotlin.math.abs(v) + 2.0)
        range = kotlin.math.ceil(range / 6.0) * 6.0
        fun y(db: Double) = top + (ph * (0.5 - db / (2 * range))).toFloat()
        labelP.textAlign = Paint.Align.CENTER
        for (f in doubleArrayOf(20.0, 100.0, 1000.0, 10000.0)) {
            val x = axis.x(f, left, pw); c.drawLine(x, top, x, top + ph, gridP)
            c.drawText(FractionalBands.formatFreq(f), x, h - context.dp(4f), labelP)
        }
        labelP.textAlign = Paint.Align.RIGHT
        var d = -range
        while (d <= range + 0.01) {
            val yy = y(d)
            c.drawLine(left, yy, left + pw, yy, if (d == 0.0) zeroP else gridP)
            c.drawText(String.format("%+.0f", d), left - context.dp(3f), yy + context.dp(3f), labelP)
            d += 6.0
        }
        if (cv == null || cv.size == 0) return
        for (pass in 0..1) {
            path.reset()
            var i = 0
            while (i <= pw.toInt()) {
                val f = axis.f(left + i, left, pw)
                val r = cv.responseAt(f)
                val v = if (pass == 0) r else -r.coerceIn(-maxCorr, maxCorr)
                val yy = y(v).coerceIn(top, top + ph)
                if (i == 0) path.moveTo(left + i, yy) else path.lineTo(left + i, yy)
                i += 2
            }
            c.drawPath(path, if (pass == 0) respP else corrP)
        }
        if (cv.size <= 40) {
            for (k in 0 until cv.size) {
                val f = cv.freqs[k]
                if (f < 20 || f > 20000) continue
                c.drawCircle(axis.x(f, left, pw), y(cv.dbs[k]), context.dp(3f), dotP)
            }
        }
    }
}

private val LOC_BR: java.util.Locale = java.util.Locale.forLanguageTag("pt-BR")

fun fmt1(v: Double): String = if (v < -100 || v > 250 || v.isNaN()) "--" else String.format(LOC_BR, "%.1f", v)
fun fmt2(v: Double): String = if (v.isNaN() || v.isInfinite()) "--" else String.format(LOC_BR, "%.2f", v)
fun fmtSigned1(v: Double): String = String.format(LOC_BR, "%+.1f", v)
fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
