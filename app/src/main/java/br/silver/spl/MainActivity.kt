package br.silver.spl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.MicrophoneInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import br.silver.spl.audio.AudioEngine
import br.silver.spl.audio.InputSource
import br.silver.spl.audio.ToneGenerator
import br.silver.spl.dsp.Analyzer
import br.silver.spl.dsp.AnalyzerConfig
import br.silver.spl.dsp.AvgMode
import br.silver.spl.dsp.FractionalBands
import br.silver.spl.dsp.MicCurve
import br.silver.spl.dsp.Snapshot
import br.silver.spl.dsp.Weighting
import br.silver.spl.ui.LevelBar
import br.silver.spl.ui.Pal
import br.silver.spl.ui.Segmented
import br.silver.spl.ui.SpectrumView
import br.silver.spl.ui.Ui
import br.silver.spl.ui.fmt1
import br.silver.spl.ui.fmtSigned1

class MainActivity : Activity() {
    lateinit var store: Store
    lateinit var engine: AudioEngine
    lateinit var ui: Ui
    val tone = ToneGenerator()
    var analyzer: Analyzer? = null
    val snap = Snapshot()
    val handler = Handler(Looper.getMainLooper())

    private var userRunning = true
    private var permissionAsked = false
    var devKey = "builtin"
    var preferredDevice: AudioDeviceInfo? = null
    var deviceSensitivity: Float? = null
    var reopenCurveDialog = false

    // views
    private lateinit var bigTv: TextView
    private lateinit var unitTv: TextView
    private lateinit var twTv: TextView
    private lateinit var clipTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var leqTv: TextView
    private lateinit var maxTv: TextView
    private lateinit var minTv: TextView
    private lateinit var peakTv: TextView
    private lateinit var tripleTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var levelBar: LevelBar
    lateinit var spectrum: SpectrumView
    private lateinit var startBtn: TextView
    private lateinit var avgBtn: TextView
    private lateinit var peakBtn: TextView
    private lateinit var freezeBtn: TextView
    private lateinit var wSeg: Segmented
    private lateinit var tSeg: Segmented
    private lateinit var modeSeg: Segmented

    private var tick = 0

    companion object {
        const val REQ_MIC = 10
        const val REQ_IMPORT = 20
        const val REQ_EXPORT = 21
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        engine = AudioEngine(this)
        ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Pal.BG
            @Suppress("DEPRECATION")
            window.navigationBarColor = Pal.BG
        }
        setContentView(buildLayout())
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = ui.vbox(10, 8).apply { setBackgroundColor(Pal.BG) }
        // Android 15+ desenha de borda a borda: respeitar barra de status, navegação e recorte da câmera
        val pad = ui.dp(10); val padV = ui.dp(8)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + i.left, padV + i.top, pad + i.right, padV + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(pad + insets.systemWindowInsetLeft, padV + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, padV + insets.systemWindowInsetBottom)
            }
            insets
        }

        // cabeçalho
        val header = ui.hbox()
        val titleBox = ui.vbox()
        titleBox.addView(ui.text("SilverSPL", 22f, Pal.SILVER_HI, true))
        statusTv = ui.text("Analisador de espectro e SPL", 11f, Pal.TEXT_DIM)
        titleBox.addView(statusTv)
        header.addView(titleBox, ui.lp(0, weight = 1f))
        header.addView(ui.darkButton("⋮ Opções") { showOptions() })
        root.addView(header, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mb = 8))

        // cartão do medidor
        val meter = ui.card()
        val row1 = ui.hbox()
        bigTv = ui.text("--", 62f, Pal.SILVER_HI, true).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
        }
        row1.addView(bigTv)
        val unitBox = ui.vbox().apply { setPadding(ui.dp(8), 0, 0, 0) }
        unitTv = ui.text("dB(A)", 20f, Pal.SILVER, true)
        twTv = ui.text("FAST", 13f, Pal.TEXT_DIM, true)
        unitBox.addView(unitTv); unitBox.addView(twTv)
        row1.addView(unitBox)
        row1.addView(ui.weightSpacer())
        val rightBox = ui.vbox().apply { gravity = Gravity.END }
        clipTv = ui.text("SATURAÇÃO", 12f, Pal.RED, true).apply { visibility = View.INVISIBLE }
        timeTv = ui.text("00:00", 13f, Pal.TEXT_DIM)
        rightBox.addView(clipTv); rightBox.addView(timeTv)
        row1.addView(rightBox)
        meter.addView(row1)

        levelBar = LevelBar(this)
        meter.addView(levelBar, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(26), mt = 4, mb = 6))

        val stats = ui.hbox()
        fun stat(label: String): TextView {
            val b = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            b.addView(ui.text(label, 11f, Pal.TEXT_DIM))
            val v = ui.text("--", 17f, Pal.TEXT, true)
            b.addView(v)
            stats.addView(b, ui.lp(0, weight = 1f))
            return v
        }
        leqTv = stat("Leq"); maxTv = stat("Máx"); minTv = stat("Mín"); peakTv = stat("Pico Z")
        meter.addView(stats)
        tripleTv = ui.text("LA --   LC --   LZ --", 12f, Pal.TEXT_DIM).apply { gravity = Gravity.CENTER }
        meter.addView(tripleTv, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 2, mb = 6))

        val chips = ui.hbox()
        wSeg = Segmented(this, listOf("A", "C", "Z"), store.meterWeighting.ordinal) {
            store.meterWeighting = Weighting.values()[it]; refreshTexts()
        }
        tSeg = Segmented(this, listOf("Fast", "Slow"), if (store.slow) 1 else 0) {
            store.slow = it == 1; refreshTexts()
        }
        chips.addView(wSeg)
        chips.addView(ui.spacer(8))
        chips.addView(tSeg)
        chips.addView(ui.weightSpacer())
        chips.addView(ui.darkButton("Zerar") { analyzer?.resetStats(); analyzer?.resetPeakHold() })
        meter.addView(chips)
        root.addView(meter, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mb = 8))

        // cartão do espectro
        val specCard = ui.card()
        val ctl = ui.hbox()
        val modeIdx = when (store.bandsPerOctave) { 1 -> 0; 3 -> 1; 6 -> 2; else -> 3 }
        modeSeg = Segmented(this, listOf("1/1", "1/3", "1/6", "FFT"), modeIdx) {
            store.bandsPerOctave = when (it) { 0 -> 1; 1 -> 3; 2 -> 6; else -> 0 }
            pushConfig()
        }
        ctl.addView(modeSeg)
        ctl.addView(ui.weightSpacer())
        avgBtn = ui.darkButton("") { cycleAvg() }
        peakBtn = ui.darkButton("Pico") { store.peakHold = !store.peakHold; analyzer?.resetPeakHold(); updateToggles() }
        freezeBtn = ui.darkButton("❄") { analyzer?.let { it.frozen = !it.frozen }; updateToggles() }
        ctl.addView(avgBtn); ctl.addView(ui.spacer(4)); ctl.addView(peakBtn); ctl.addView(ui.spacer(4)); ctl.addView(freezeBtn)
        specCard.addView(ctl, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mb = 6))
        spectrum = SpectrumView(this).apply { snap = this@MainActivity.snap }
        spectrum.setOnLongClickListener { analyzer?.resetPeakHold(); spectrum.clearCursor(); true }
        specCard.addView(spectrum, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        infoTv = ui.text("", 11f, Pal.TEXT_DIM).apply { setPadding(0, ui.dp(5), 0, 0) }
        specCard.addView(infoTv)
        root.addView(specCard, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f, mb = 8))

        // barra inferior
        val bar = ui.hbox()
        startBtn = ui.button("■ Parar") { toggleRun() }
        bar.addView(startBtn, ui.lp(0, weight = 1.1f, mr = 4))
        bar.addView(ui.button("Curva") { showCurveDialog() }, ui.lp(0, weight = 1f, mr = 4))
        bar.addView(ui.button("Calibrar") { showCalibrationDialog() }, ui.lp(0, weight = 1.1f, mr = 4))
        bar.addView(ui.button("Gerador") { showToneDialog() }, ui.lp(0, weight = 1.1f))
        root.addView(bar, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT))

        updateToggles()
        return root
    }

    private fun cycleAvg() {
        val v = AvgMode.values()
        store.avgMode = v[(store.avgMode.ordinal + 1) % v.size]
        pushConfig(); updateToggles()
    }

    fun updateToggles() {
        avgBtn.text = store.avgMode.label
        peakBtn.setTextColor(if (store.peakHold) Pal.PEAK else Pal.TEXT_DIM)
        freezeBtn.setTextColor(if (analyzer?.frozen == true) Pal.ACCENT else Pal.TEXT_DIM)
        spectrum.showPeak = store.peakHold
        spectrum.bandMode = store.bandsPerOctave != 0
        spectrum.yMin = store.yMin.toFloat(); spectrum.yMax = store.yMax.toFloat()
        spectrum.unitLabel = if (store.rtaWeighting == Weighting.Z) "dB" else "dB(${store.rtaWeighting.label})"
        spectrum.invalidate()
    }

    // ---------------------------------------------------------------- ciclo de vida / áudio

    override fun onResume() {
        super.onResume()
        if (userRunning) startAudio()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        tone.stop()
        stopAudio()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshFrame()
            handler.postDelayed(this, 33)
        }
    }

    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startAudio() {
        if (!hasMicPermission()) {
            if (!permissionAsked) { permissionAsked = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
            else statusTv.text = "Permissão do microfone negada"
            return
        }
        preferredDevice = findPreferredDevice()
        devKey = AudioEngine.deviceKey(preferredDevice)
        val an = engine.start(store.source, preferredDevice)
        analyzer = an
        if (an == null) {
            statusTv.text = "Erro: ${engine.lastError}"
            startBtn.text = "▶ Iniciar"
            return
        }
        engine.lastError?.let { toast(it) }
        pushConfig()
        startBtn.text = "■ Parar"
        updateToggles()
        // o HAL só informa os microfones ativos depois que o áudio começa a fluir
        handler.postDelayed({ readDeviceMicInfo() }, 700)
    }

    fun stopAudio() {
        engine.stop()
        analyzer = null
    }

    fun restartAudio() {
        if (engine.isRunning) { stopAudio(); startAudio() }
    }

    private fun toggleRun() {
        if (engine.isRunning) { userRunning = false; stopAudio(); startBtn.text = "▶ Iniciar"; statusTv.text = "Parado" }
        else { userRunning = true; startAudio() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startAudio()
            else statusTv.text = "Sem permissão do microfone — habilite nas configurações do Android"
        }
    }

    private fun findPreferredDevice(): AudioDeviceInfo? {
        val key = store.inputDeviceKey
        if (key == "builtin") return engine.inputDevices().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return engine.inputDevices().firstOrNull { AudioEngine.deviceKey(it) == key }
    }

    private fun readDeviceMicInfo() {
        val mics = engine.activeMicrophones()
        deviceSensitivity = null
        for (m in mics) {
            if (m.sensitivity != MicrophoneInfo.SENSITIVITY_UNKNOWN && deviceSensitivity == null) deviceSensitivity = m.sensitivity
            val fr = m.frequencyResponse
            if (fr != null && fr.size >= 2) {
                val pts = fr.map { it.first.toDouble() to it.second.toDouble() }
                val c = MicCurve(MicCurve.ID_DEVICE, "Do aparelho (${m.description})", pts,
                    "Informada pelo próprio Android (MicrophoneInfo), ${pts.size} pontos.")
                store.saveCurve(c.normalizedAt1k().first)
                break
            }
        }
    }

    // ---------------------------------------------------------------- configuração

    fun currentOffset(): Double = store.offset(engine.activeSource, devKey)

    fun curveById(id: String): MicCurve? = when (id) {
        MicCurve.ID_FLAT -> MicCurve.flat
        MicCurve.ID_MEMS_TYP -> MicCurve.memsTypical
        MicCurve.ID_MEMS_STRONG -> MicCurve.memsStrong
        else -> store.loadCurve(id)
    }

    fun currentCurve(): MicCurve = curveById(store.curveId) ?: MicCurve.flat

    fun pushConfig() {
        val an = analyzer ?: return
        an.config = AnalyzerConfig(
            curve = currentCurve(),
            applyCurve = store.applyCurve,
            maxCorrectionDb = store.maxCorrection,
            offsetDb = currentOffset(),
            displayN = store.displayN,
            bandsPerOctave = if (store.bandsPerOctave == 0) 3 else store.bandsPerOctave,
            rtaWeighting = store.rtaWeighting,
            avgMode = store.avgMode
        )
        updateToggles()
        updateStatus()
    }

    fun updateStatus() {
        val src = engine.activeSource
        val off = currentOffset()
        val adj = off - src.nominalOffset
        val dev = if (engine.routedDeviceName.isNotBlank()) engine.routedDeviceName else "Microfone interno"
        statusTv.text = "${src.name} · $dev · ${engine.sampleRate / 1000} kHz"
        val c = currentCurve()
        val curveTxt = if (!store.applyCurve || c.isFlat) "sem correção" else c.name
        infoTv.text = "Curva: $curveTxt · Offset ${fmt1(off)} dB (${fmtSigned1(adj)}) · FFT ${store.displayN}"
    }

    // ---------------------------------------------------------------- atualização da tela

    private fun refreshFrame() {
        val an = analyzer ?: return
        an.copySnapshot(snap)
        spectrum.invalidate()
        if (tick++ % 4 == 0) refreshTexts()
    }

    fun refreshTexts() {
        if (!snap.valid || analyzer == null) return
        val w = store.meterWeighting
        val wi = w.ordinal
        val slow = store.slow
        val lvl = if (slow) snap.slow[wi] else snap.fast[wi]
        bigTv.text = fmt1(lvl)
        unitTv.text = if (w == Weighting.Z) "dB(Z)" else "dB(${w.label})"
        twTv.text = if (slow) "SLOW" else "FAST"
        leqTv.text = fmt1(snap.leq[wi])
        val mx = if (slow) snap.maxS[wi] else snap.maxF[wi]
        val mn = if (slow) snap.minS[wi] else snap.minF[wi]
        maxTv.text = fmt1(mx); minTv.text = fmt1(mn); peakTv.text = fmt1(snap.peakZMax)
        val arr = if (slow) snap.slow else snap.fast
        tripleTv.text = "LA ${fmt1(arr[0])}   LC ${fmt1(arr[1])}   LZ ${fmt1(arr[2])}   ·   Dominante ${domTxt()}"
        levelBar.level = lvl; levelBar.maxLevel = mx; levelBar.clip = snap.clipping
        levelBar.invalidate()
        clipTv.visibility = if (snap.clipping) View.VISIBLE else View.INVISIBLE
        val t = snap.elapsedSec.toInt()
        timeTv.text = String.format("%02d:%02d", t / 60, t % 60)
    }

    private fun domTxt(): String {
        val f = snap.dominantHz
        return if (f <= 0) "--" else if (f >= 1000) String.format("%.2f kHz", f / 1000) else String.format("%.0f Hz", f)
    }

    // ---------------------------------------------------------------- opções

    private fun showOptions() {
        val items = arrayOf(
            "Fonte de áudio: ${store.source.name}",
            "Entrada: ${if (store.inputDeviceKey == "builtin") "microfone interno" else "externa"}",
            "Resolução da FFT: ${store.displayN} pontos",
            "Ponderação do espectro: ${store.rtaWeighting.label}",
            "Escala do gráfico: ${store.yMin}–${store.yMax} dB",
            "Informações do microfone",
            "Ajuda"
        )
        dialog().setTitle("Opções").setItems(items) { _, which ->
            when (which) {
                0 -> chooseSource()
                1 -> chooseInput()
                2 -> chooseFft()
                3 -> chooseRtaWeighting()
                4 -> chooseScale()
                5 -> showMicInfo()
                6 -> showHelp()
            }
        }.show()
    }

    private fun chooseSource() {
        val sup = engine.unprocessedSupported()
        val v = InputSource.values()
        val labels = v.map {
            if (it == InputSource.UNPROCESSED) it.label + if (sup) "  ✓ suportado" else "  (aparelho não declara suporte)" else it.label
        }.toTypedArray()
        dialog().setTitle("Fonte de áudio").setSingleChoiceItems(labels, store.source.ordinal) { d, which ->
            store.source = v[which]; d.dismiss(); restartAudio()
        }.show()
    }

    private fun chooseInput() {
        val devs = engine.inputDevices()
        val labels = ArrayList<String>()
        val keys = ArrayList<String>()
        labels.add("Microfone interno (padrão)"); keys.add("builtin")
        for (d in devs) {
            if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || d.type == AudioDeviceInfo.TYPE_TELEPHONY) continue
            val k = AudioEngine.deviceKey(d)
            if (keys.contains(k)) continue
            labels.add(AudioEngine.describe(d)); keys.add(k)
        }
        val sel = keys.indexOf(store.inputDeviceKey).coerceAtLeast(0)
        dialog().setTitle("Entrada de áudio").setSingleChoiceItems(labels.toTypedArray(), sel) { d, which ->
            store.inputDeviceKey = keys[which]; d.dismiss(); restartAudio()
            if (keys[which] != "builtin") toast("Entrada externa tem calibração própria (offset separado)")
        }.show()
    }

    private fun chooseFft() {
        val opts = intArrayOf(4096, 8192, 16384)
        val labels = opts.map { n -> "$n pontos (${String.format("%.1f", 48000.0 / n)} Hz por bin)" }.toTypedArray()
        dialog().setTitle("Resolução da FFT").setSingleChoiceItems(labels, opts.indexOf(store.displayN).coerceAtLeast(0)) { d, w ->
            store.displayN = opts[w]; d.dismiss(); pushConfig()
        }.show()
    }

    private fun chooseRtaWeighting() {
        val v = Weighting.values()
        dialog().setTitle("Ponderação do espectro").setSingleChoiceItems(v.map { "Ponderação ${it.label}" }.toTypedArray(),
            store.rtaWeighting.ordinal) { d, w -> store.rtaWeighting = v[w]; d.dismiss(); pushConfig() }.show()
    }

    private fun chooseScale() {
        val opts = listOf(0 to 110, 20 to 100, 30 to 130, -10 to 90, 40 to 120)
        dialog().setTitle("Escala do gráfico").setItems(opts.map { "${it.first} a ${it.second} dB" }.toTypedArray()) { _, w ->
            store.yMin = opts[w].first; store.yMax = opts[w].second; updateToggles()
        }.show()
    }

    private fun showMicInfo() {
        val sb = StringBuilder()
        sb.append("Fonte em uso: ${engine.activeSource.name}\n")
        sb.append("UNPROCESSED declarado: ${if (engine.unprocessedSupported()) "sim" else "não"}\n")
        sb.append("Taxa: ${engine.sampleRate} Hz\n")
        sb.append("Roteado para: ${engine.routedDeviceName.ifBlank { "?" }}\n")
        sb.append("Sensibilidade nominal (CDD): ${engine.activeSource.nominalNote}\n\n")
        val mics = engine.activeMicrophones()
        if (mics.isEmpty()) sb.append("O Android não informou dados dos microfones ativos (MicrophoneInfo vazio).\n")
        for (m in mics) {
            sb.append("• ${m.description} (id ${m.id})\n")
            sb.append("   Tipo: ${AudioEngine.typeName(m.type)} · posição: ${loc(m.location)}\n")
            sb.append("   Sensibilidade: ${if (m.sensitivity == MicrophoneInfo.SENSITIVITY_UNKNOWN) "não informada" else "${m.sensitivity} dBFS @ 94 dB SPL"}\n")
            sb.append("   SPL máx: ${if (m.maxSpl == MicrophoneInfo.SPL_UNKNOWN) "?" else m.maxSpl} · SPL mín: ${if (m.minSpl == MicrophoneInfo.SPL_UNKNOWN) "?" else m.minSpl}\n")
            sb.append("   Resposta em frequência: ${m.frequencyResponse?.size ?: 0} pontos\n")
        }
        val all = engine.allMicrophones()
        if (all.isNotEmpty()) {
            sb.append("\nMicrofones do aparelho: ${all.size}\n")
            for (m in all) sb.append("  - ${m.description} (${loc(m.location)})\n")
        }
        val b = dialog().setTitle("Informações do microfone").setMessage(sb.toString()).setPositiveButton("OK", null)
        val sens = deviceSensitivity
        if (sens != null) b.setNeutralButton("Usar sensibilidade") { _, _ ->
            store.setOffset(engine.activeSource, devKey, 94.0 - sens); pushConfig()
            toast("Offset = 94 − (${sens}) = ${fmt1(94.0 - sens)} dB")
        }
        b.show()
    }

    private fun loc(l: Int): String = when (l) {
        MicrophoneInfo.LOCATION_MAINBODY -> "corpo do aparelho"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "parte móvel"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "periférico"
        else -> "desconhecida"
    }

    private fun showHelp() {
        val msg = """
            COMO USAR
            • O número grande é o nível sonoro com a ponderação (A/C/Z) e o tempo (Fast/Slow) escolhidos. Leq é a média energética desde o último “Zerar”.
            • Toque no gráfico para ler frequência e nível; toque longo zera o pico.
            • 1/1, 1/3, 1/6 = bandas de oitava; FFT = espectro de banda estreita.

            PRECISÃO
            • A fonte UNPROCESSED desliga o processamento de voz do Android. A sensibilidade de fábrica segue o padrão do Android (94 dB = −36 dBFS), mas cada aparelho varia alguns dB: por isso existe o botão Calibrar.
            • Aponte a borda inferior do celular (microfone principal) para a fonte e não cubra o furo.
            • O celular satura perto de 110–120 dB SPL e tem ruído próprio perto de 30–35 dB(A).

            CURVAS
            • As curvas “estimadas” são modelos genéricos de microfone MEMS, não medições do A55. A forma mais precisa é a curva “Medida com decibelímetro” (assistente na tela Curva) ou importar um arquivo de calibração (formato REW/miniDSP: frequência e dB por linha).

            AJUSTE EXTERNO (Calibrar)
            • Compare com um decibelímetro ou calibrador acústico (94/114 dB). O ajuste fica salvo por fonte de áudio e por entrada.
        """.trimIndent()
        dialog().setTitle("Ajuda").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- utilidades

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun dialogFrame(content: View): View {
        val sv = android.widget.ScrollView(this)
        val box = ui.vbox(18, 6)
        box.addView(content)
        sv.addView(box)
        return sv
    }

    // ---------------------------------------------------------------- arquivos

    fun importCurve() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream", "*/*"))
        }
        reopenCurveDialog = true
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    fun exportCurve() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "SilverSPL_curva.txt")
        }
        reopenCurveDialog = true
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            try {
                when (requestCode) {
                    REQ_IMPORT -> {
                        val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        val name = displayName(uri) ?: "arquivo"
                        val c = MicCurve.parse(txt, MicCurve.ID_FILE, "Arquivo: $name")
                        if (c.size < 2) toast("Arquivo sem pontos válidos (esperado: frequência e dB por linha)")
                        else {
                            store.saveCurve(c); store.curveId = MicCurve.ID_FILE; store.applyCurve = true
                            pushConfig()
                            toast("Curva importada: ${c.size} pontos (${FractionalBands.formatFreq(c.freqs.first())}–${FractionalBands.formatFreq(c.freqs.last())} Hz)")
                        }
                    }
                    REQ_EXPORT -> {
                        contentResolver.openOutputStream(uri)?.use { it.write(currentCurve().toText().toByteArray()) }
                        toast("Curva exportada")
                    }
                }
            } catch (e: Exception) {
                toast("Erro: ${e.message}")
            }
        }
        if (reopenCurveDialog) { reopenCurveDialog = false; handler.postDelayed({ showCurveDialog() }, 300) }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }
}
