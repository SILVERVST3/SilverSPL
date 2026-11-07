package br.silver.spl

import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import br.silver.spl.audio.Signal
import br.silver.spl.dsp.FractionalBands
import br.silver.spl.dsp.MicCurve
import br.silver.spl.dsp.Weighting
import br.silver.spl.dsp.Weightings
import br.silver.spl.ui.CurveView
import br.silver.spl.ui.Pal
import br.silver.spl.ui.Segmented
import br.silver.spl.ui.fmt1
import br.silver.spl.ui.fmtSigned1
import br.silver.spl.ui.parseNum

/** Frequências de 1/3 de oitava usadas no assistente e no gerador. */
private val THIRD_OCT: List<Double> = FractionalBands(3, 24.0, 20500.0).centers.map { FractionalBands.nominal(it) }

private fun freqLabel(f: Double) = FractionalBands.formatFreq(f) + " Hz"

// ======================================================================= CURVA

fun MainActivity.showCurveDialog() {
    val box = ui.vbox()
    box.addView(ui.text("Escolha a curva de resposta do microfone. O app subtrai essa curva do sinal (correção).", 13f, Pal.TEXT_DIM))

    val preview = CurveView(this).apply { maxCorr = store.maxCorrection }
    val noteTv = ui.text("", 12f, Pal.TEXT_DIM)
    val legend = ui.text("— resposta do mic   - - correção aplicada", 11f, Pal.TEXT_DIM)

    val ids = listOf(MicCurve.ID_FLAT, MicCurve.ID_MEMS_TYP, MicCurve.ID_MEMS_STRONG, MicCurve.ID_DEVICE, MicCurve.ID_FILE, MicCurve.ID_USER)
    val group = RadioGroup(this)
    fun describe(id: String): Pair<String, Boolean> {
        val c = curveById(id)
        return when (id) {
            MicCurve.ID_DEVICE -> if (c != null) "Do aparelho (MicrophoneInfo, ${c.size} pts)" to true else "Do aparelho — não informada pelo Android" to false
            MicCurve.ID_FILE -> if (c != null) "${c.name} (${c.size} pts)" to true else "Arquivo importado — nenhum" to false
            MicCurve.ID_USER -> if (c != null) "Medida com decibelímetro (${c.size} pts)" to true else "Medida com decibelímetro — ainda não medida" to false
            else -> (c?.name ?: id) to true
        }
    }
    fun showPreview(id: String) {
        val c = curveById(id)
        preview.curve = c; preview.invalidate()
        noteTv.text = c?.note ?: ""
    }
    ids.forEachIndexed { i, id ->
        val (label, enabled) = describe(id)
        val rb = RadioButton(this).apply {
            text = label; this.id = 1000 + i; isEnabled = enabled
            setTextColor(if (enabled) Pal.TEXT else Pal.TEXT_DIM); textSize = 14f
        }
        group.addView(rb)
        if (id == store.curveId && enabled) rb.isChecked = true
    }
    group.setOnCheckedChangeListener { _, checkedId ->
        val id = ids[checkedId - 1000]
        store.curveId = id
        showPreview(id); pushConfig()
    }
    box.addView(group)
    box.addView(preview, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(170), mt = 6))
    box.addView(legend)
    box.addView(noteTv)

    val apply = CheckBox(this).apply {
        text = "Aplicar correção da curva"; isChecked = store.applyCurve; setTextColor(Pal.TEXT)
        setOnCheckedChangeListener { _: CompoundButton, b: Boolean -> store.applyCurve = b; pushConfig() }
    }
    box.addView(apply)
    val limTv = ui.text("Limite de correção: ±${store.maxCorrection.toInt()} dB", 13f, Pal.TEXT)
    box.addView(limTv)
    val sb = SeekBar(this).apply {
        max = 24; progress = (store.maxCorrection.toInt() - 6).coerceIn(0, 24)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                store.maxCorrection = (p + 6).toDouble(); limTv.text = "Limite de correção: ±${p + 6} dB"
                preview.maxCorr = store.maxCorrection; preview.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { pushConfig() }
        })
    }
    box.addView(sb)

    var dlg: AlertDialog? = null
    val row = ui.hbox()
    row.addView(ui.darkButton("Importar…") { dlg?.dismiss(); importCurve() }, ui.lp(0, weight = 1f, mr = 4))
    row.addView(ui.darkButton("Exportar…") { dlg?.dismiss(); exportCurve() }, ui.lp(0, weight = 1f))
    box.addView(row, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8))
    box.addView(ui.button("Medir curva com decibelímetro…") { dlg?.dismiss(); showCurveWizard() },
        ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8, mb = 6))

    showPreview(store.curveId)
    dlg = dialog().setTitle("Curva do microfone").setView(dialogFrame(box)).setPositiveButton("OK", null).show()
}

// ======================================================================= CALIBRAÇÃO (ajuste externo)

fun MainActivity.showCalibrationDialog() {
    if (analyzer == null) { toast("Inicie a medição primeiro"); return }
    val src = engine.activeSource
    val box = ui.vbox()
    val curTv = ui.text("", 14f, Pal.SILVER_HI, true)
    fun updateCur() {
        val off = currentOffset()
        curTv.text = "Offset atual: ${fmt1(off)} dB\n(nominal ${fmt1(src.nominalOffset)} · ajuste ${fmtSigned1(off - src.nominalOffset)} dB)"
    }
    updateCur()
    box.addView(curTv)
    box.addView(ui.text("Fonte ${src.name}${if (devKey != "builtin") " · entrada externa" else ""}. O ajuste é salvo separadamente para cada fonte/entrada.", 12f, Pal.TEXT_DIM))

    // ---- 1: decibelímetro
    box.addView(ui.section("1 · Comparar com um decibelímetro"))
    box.addView(ui.text("Coloque o microfone do celular (borda de baixo) colado ao microfone do decibelímetro, ambos voltados para a fonte. " +
            "Use um som estável — ruído rosa do Gerador numa caixa de som é o ideal. No decibelímetro use a mesma ponderação escolhida aqui e o modo Slow (ou Leq).", 12.5f, Pal.TEXT_DIM))
    val wSel = Segmented(this, listOf("dB(A)", "dB(C)", "dB(Z)"), 0) {}
    val durSel = Segmented(this, listOf("5 s", "10 s", "30 s"), 1) {}
    box.addView(ui.text("Ponderação do decibelímetro:", 12.5f, Pal.TEXT_DIM), ui.lp(mt = 6))
    box.addView(wSel)
    box.addView(ui.text("Duração da média:", 12.5f, Pal.TEXT_DIM), ui.lp(mt = 4))
    box.addView(durSel)
    val measTv = ui.text("Pressione Medir.", 14f, Pal.TEXT)
    val refEt = ui.edit("Leitura do decibelímetro (dB)")
    var measuredDbfs = Double.NaN
    var running: Runnable? = null

    fun capture(durSec: Double, start: () -> Unit, onDone: (Double) -> Unit, label: TextView) {
        val an = analyzer ?: return
        running?.let { handler.removeCallbacks(it) }
        start()
        val r = object : Runnable {
            override fun run() {
                val a = analyzer
                if (a == null || a !== an) { label.text = "Medição interrompida."; return }
                val t = a.captureSeconds()
                val partial = a.captureResultDbfs() + currentOffset()
                if (t >= durSec) {
                    val res = a.captureResultDbfs()
                    a.stopCapture()
                    onDone(res)
                } else {
                    label.text = String.format("Medindo… %.1f / %.0f s   (parcial %s dB)", t, durSec, fmt1(partial))
                    handler.postDelayed(this, 150)
                }
            }
        }
        running = r
        handler.postDelayed(r, 150)
    }

    val measRow = ui.hbox()
    measRow.addView(ui.button("Medir") {
        val w = Weighting.values()[wSel.selectedIndex]
        val dur = doubleArrayOf(5.0, 10.0, 30.0)[durSel.selectedIndex]
        capture(dur, { analyzer?.startBroadbandCapture(w) }, { dbfs ->
            measuredDbfs = dbfs
            measTv.text = "SilverSPL mediu: ${fmt1(dbfs + currentOffset())} dB(${w.label}) Leq\nDigite a leitura do decibelímetro e toque Aplicar."
        }, measTv)
    }, ui.lp(0, weight = 1f, mr = 4))
    measRow.addView(ui.button("Aplicar") {
        val ref = parseNum(refEt.text.toString())
        when {
            measuredDbfs.isNaN() -> toast("Meça primeiro")
            ref == null -> toast("Digite a leitura do decibelímetro")
            else -> {
                val newOff = ref - measuredDbfs
                store.setOffset(src, devKey, newOff); pushConfig(); updateCur()
                toast("Ajustado: offset ${fmt1(newOff)} dB")
            }
        }
    }, ui.lp(0, weight = 1f))
    box.addView(measTv, ui.lp(mt = 6))
    box.addView(refEt)
    box.addView(measRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 4))

    // ---- 2: calibrador acústico
    box.addView(ui.section("2 · Calibrador acústico (1 kHz)"))
    box.addView(ui.text("Encaixe/encoste o calibrador no furo do microfone e ligue-o. A medição dura 5 s em dB(Z).", 12.5f, Pal.TEXT_DIM))
    val calTv = ui.text("", 14f, Pal.TEXT)
    val calRow = ui.hbox()
    for (target in listOf(94.0, 114.0)) {
        calRow.addView(ui.button("${target.toInt()} dB") {
            capture(5.0, { analyzer?.startBroadbandCapture(Weighting.Z) }, { dbfs ->
                val newOff = target - dbfs
                calTv.text = "Medido ${fmt1(dbfs + currentOffset())} dB → novo offset ${fmt1(newOff)} dB"
                dialog().setTitle("Aplicar calibração?")
                    .setMessage("O SilverSPL leu ${fmt1(dbfs + currentOffset())} dB com o calibrador de ${target.toInt()} dB.\nNovo offset: ${fmt1(newOff)} dB.")
                    .setPositiveButton("Aplicar") { _, _ -> store.setOffset(src, devKey, newOff); pushConfig(); updateCur() }
                    .setNegativeButton("Cancelar", null).show()
            }, calTv)
        }, ui.lp(0, weight = 1f, mr = 4))
    }
    box.addView(calRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 6))
    box.addView(calTv)

    // ---- 3: ajuste manual
    box.addView(ui.section("3 · Ajuste fino manual"))
    val adjRow = ui.hbox()
    for (d in listOf(-1.0, -0.1, 0.1, 1.0)) {
        adjRow.addView(ui.darkButton(fmtSigned1(d)) {
            store.setOffset(src, devKey, currentOffset() + d); pushConfig(); updateCur()
        }, ui.lp(0, weight = 1f, mr = 4))
    }
    box.addView(adjRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT))
    val r3 = ui.hbox()
    r3.addView(ui.darkButton("Restaurar nominal") {
        store.resetOffset(src, devKey); pushConfig(); updateCur()
    }, ui.lp(0, weight = 1f, mr = 4))
    val sens = deviceSensitivity
    if (sens != null) {
        r3.addView(ui.darkButton("Sensib. do aparelho") {
            store.setOffset(src, devKey, 94.0 - sens); pushConfig(); updateCur()
        }, ui.lp(0, weight = 1f))
    }
    box.addView(r3, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 6, mb = 8))

    dialog().setTitle("Calibrar (ajuste externo)").setView(dialogFrame(box))
        .setPositiveButton("Fechar", null)
        .setOnDismissListener { running?.let { handler.removeCallbacks(it) }; analyzer?.stopCapture() }
        .show()
}

// ======================================================================= ASSISTENTE DE CURVA

/**
 * Mede a resposta do microfone ponto a ponto contra um decibelímetro, com tons puros.
 * Cada ponto guarda a "sensibilidade" s(f) = dBFS_medido − SPL_referência(Z), independente do offset.
 * Ao salvar: curva = s(f) − s(1 kHz); offset = −s(1 kHz).
 */
fun MainActivity.showCurveWizard() {
    if (analyzer == null) { toast("Inicie a medição primeiro"); return }
    val pts = store.wizardPoints.toMutableList()
    var fIdx = THIRD_OCT.indexOfFirst { it >= 1000.0 }.coerceAtLeast(0)
    var lastDbfs = Double.NaN
    var lastF = 0.0
    var running: Runnable? = null

    val box = ui.vbox()
    box.addView(ui.text("1) Toque um TOM PURO na frequência escolhida (botão Tocar tom com uma caixa de som externa ligada ao celular, ou um gerador externo).\n" +
            "2) Deixe o decibelímetro colado ao microfone do celular. Ajuste o volume para ficar bem acima do ruído ambiente (ex.: 75–90 dB).\n" +
            "3) Toque Medir, digite a leitura do decibelímetro e Adicionar ponto. Repita em várias frequências — inclua 1 kHz.", 12.5f, Pal.TEXT_DIM))

    val fTv = ui.text("", 22f, Pal.SILVER_HI, true).apply { gravity = Gravity.CENTER }
    val fRow = ui.hbox()
    fRow.addView(ui.darkButton("  ◀  ") { if (fIdx > 0) { fIdx--; fTv.text = freqLabel(THIRD_OCT[fIdx]); if (tone.isPlaying) tone.frequency = THIRD_OCT[fIdx] } })
    fRow.addView(fTv, ui.lp(0, weight = 1f))
    fRow.addView(ui.darkButton("  ▶  ") { if (fIdx < THIRD_OCT.size - 1) { fIdx++; fTv.text = freqLabel(THIRD_OCT[fIdx]); if (tone.isPlaying) tone.frequency = THIRD_OCT[fIdx] } })
    fTv.text = freqLabel(THIRD_OCT[fIdx])
    box.addView(fRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8))

    box.addView(ui.text("Ponderação em uso no decibelímetro:", 12.5f, Pal.TEXT_DIM))
    val wSel = Segmented(this, listOf("dB(A)", "dB(C)", "dB(Z)"), 1) {}
    box.addView(wSel)

    val toneBtn = ui.darkButton("♪ Tocar tom") {}
    fun updToneBtn() { toneBtn.text = if (tone.isPlaying) "■ Parar tom" else "♪ Tocar tom" }
    toneBtn.setOnClickListener {
        if (tone.isPlaying) tone.stop() else { tone.signal = Signal.SINE; tone.frequency = THIRD_OCT[fIdx]; tone.start() }
        updToneBtn()
    }
    updToneBtn()
    val resTv = ui.text("", 14f, Pal.TEXT)
    val refEt = ui.edit("Leitura do decibelímetro (dB)")
    val ptsTv = ui.text("", 12f, Pal.TEXT_DIM)
    val preview = CurveView(this)

    fun refreshPts() {
        pts.sortBy { it.first }
        store.wizardPoints = pts
        if (pts.isEmpty()) { ptsTv.text = "Nenhum ponto."; preview.curve = null; preview.invalidate(); return }
        val tmp = MicCurve("tmp", "tmp", pts)
        val ref1k = tmp.responseAt(1000.0)
        ptsTv.text = "Pontos (resposta relativa a 1 kHz):\n" + pts.joinToString("   ") {
            "${FractionalBands.formatFreq(it.first)}: ${fmtSigned1(it.second - ref1k)}"
        }
        preview.curve = MicCurve("tmp", "tmp", pts.map { it.first to it.second - ref1k }); preview.invalidate()
    }

    val actRow = ui.hbox()
    actRow.addView(toneBtn, ui.lp(0, weight = 1f, mr = 4))
    actRow.addView(ui.button("Medir 5 s") {
        val an = analyzer ?: return@button
        val f = THIRD_OCT[fIdx]
        running?.let { handler.removeCallbacks(it) }
        an.startBandCapture(f)
        val r = object : Runnable {
            override fun run() {
                val a = analyzer
                if (a == null || a !== an) { resTv.text = "Medição interrompida."; return }
                val t = a.captureSeconds()
                if (t >= 5.0) {
                    lastDbfs = a.captureResultDbfs(); lastF = f; a.stopCapture()
                    resTv.text = "SilverSPL (sem curva, banda de ${freqLabel(f)}): ${fmt1(lastDbfs + currentOffset())} dB"
                } else {
                    resTv.text = String.format("Medindo %s… %.1f s  (%s dB)", freqLabel(f), t, fmt1(a.captureResultDbfs() + currentOffset()))
                    handler.postDelayed(this, 150)
                }
            }
        }
        running = r
        handler.postDelayed(r, 150)
    }, ui.lp(0, weight = 1f))
    box.addView(actRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8))
    box.addView(resTv, ui.lp(mt = 6))
    box.addView(refEt)

    val addRow = ui.hbox()
    addRow.addView(ui.button("Adicionar ponto") {
        val ref = parseNum(refEt.text.toString())
        if (lastDbfs.isNaN()) { toast("Meça primeiro"); return@button }
        if (ref == null) { toast("Digite a leitura do decibelímetro"); return@button }
        val w = Weighting.values()[wSel.selectedIndex]
        // leitura do medidor convertida para dB(Z) na frequência do tom
        val refZ = ref - Weightings.db(w, lastF)
        val s = lastDbfs - refZ
        pts.removeAll { kotlin.math.abs(it.first - lastF) < 0.01 }
        pts.add(lastF to s)
        refreshPts()
        refEt.setText("")
        lastDbfs = Double.NaN
        if (fIdx < THIRD_OCT.size - 1) { fIdx++; fTv.text = freqLabel(THIRD_OCT[fIdx]); if (tone.isPlaying) tone.frequency = THIRD_OCT[fIdx] }
    }, ui.lp(0, weight = 1.3f, mr = 4))
    addRow.addView(ui.darkButton("Desfazer") { if (pts.isNotEmpty()) { pts.removeAt(pts.size - 1); refreshPts() } }, ui.lp(0, weight = 1f, mr = 4))
    addRow.addView(ui.darkButton("Limpar") {
        dialog().setMessage("Apagar todos os pontos medidos?").setPositiveButton("Apagar") { _, _ -> pts.clear(); refreshPts() }
            .setNegativeButton("Cancelar", null).show()
    }, ui.lp(0, weight = 1f))
    box.addView(addRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 4))
    box.addView(preview, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(150), mt = 8))
    box.addView(ptsTv, ui.lp(mt = 4, mb = 8))
    refreshPts()

    dialog().setTitle("Medir curva com decibelímetro").setView(dialogFrame(box))
        .setPositiveButton("Salvar curva") { _, _ ->
            if (pts.size < 2) { toast("Meça pelo menos 2 frequências (inclua 1 kHz)"); return@setPositiveButton }
            val sens = MicCurve("tmp", "tmp", pts)
            val s1k = sens.responseAt(1000.0)
            val curve = MicCurve(MicCurve.ID_USER, "Medida com decibelímetro", pts.map { it.first to it.second - s1k },
                "Medida no app com tons puros e decibelímetro (${pts.size} pontos).")
            store.saveCurve(curve)
            store.curveId = MicCurve.ID_USER
            store.applyCurve = true
            store.setOffset(engine.activeSource, devKey, -s1k)
            pushConfig()
            toast("Curva salva e ativada. Offset ajustado para ${fmt1(-s1k)} dB")
        }
        .setNegativeButton("Fechar", null)
        .setOnDismissListener { running?.let { handler.removeCallbacks(it) }; analyzer?.stopCapture(); tone.stop() }
        .show()
}

// ======================================================================= GERADOR

fun MainActivity.showToneDialog() {
    var fIdx = THIRD_OCT.indexOfFirst { it >= tone.frequency - 0.5 }.let { if (it < 0) THIRD_OCT.size - 1 else it }
    val box = ui.vbox()
    box.addView(ui.text("Toca pelo alto-falante ou pela saída conectada (fone, cabo, Bluetooth, USB). " +
            "Para calibrar, prefira uma caixa de som externa: o alto-falante do celular fica colado ao microfone.", 12.5f, Pal.TEXT_DIM))
    val sigSel = Segmented(this, Signal.values().map { it.label }, tone.signal.ordinal) { tone.signal = Signal.values()[it] }
    box.addView(sigSel, ui.lp(mt = 8))

    val fTv = ui.text(freqLabel(tone.frequency), 22f, Pal.SILVER_HI, true).apply { gravity = Gravity.CENTER }
    val fRow = ui.hbox()
    fun setF() { tone.frequency = THIRD_OCT[fIdx]; fTv.text = freqLabel(THIRD_OCT[fIdx]) }
    fRow.addView(ui.darkButton("  ◀  ") { if (fIdx > 0) { fIdx--; setF() } })
    fRow.addView(fTv, ui.lp(0, weight = 1f))
    fRow.addView(ui.darkButton("  ▶  ") { if (fIdx < THIRD_OCT.size - 1) { fIdx++; setF() } })
    box.addView(fRow, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8))
    val customEt = ui.edit("Frequência livre (Hz)")
    val customRow = ui.hbox()
    customRow.addView(customEt, ui.lp(0, weight = 1f))
    customRow.addView(ui.darkButton("Usar") {
        val f = parseNum(customEt.text.toString())
        if (f != null && f in 10.0..23000.0) { tone.frequency = f; fTv.text = freqLabel(f) } else toast("Entre 10 e 23000 Hz")
    })
    box.addView(customRow)

    val lvlTv = ui.text("", 13f, Pal.TEXT)
    fun updLvl() { lvlTv.text = "Nível: ${fmt1(tone.levelDbfs)} dBFS RMS" }
    updLvl()
    box.addView(lvlTv, ui.lp(mt = 8))
    box.addView(SeekBar(this).apply {
        max = 60; progress = (tone.levelDbfs + 60).toInt().coerceIn(0, 60)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tone.levelDbfs = (p - 60).toDouble(); updLvl() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    })
    val play = ui.button("") {}
    fun updPlay() { play.text = if (tone.isPlaying) "■ Parar" else "▶ Tocar" }
    play.setOnClickListener { if (tone.isPlaying) tone.stop() else tone.start(); updPlay() }
    updPlay()
    box.addView(play, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, mt = 8, mb = 8))

    val d = dialog().setTitle("Gerador de sinais").setView(dialogFrame(box))
        .setPositiveButton("Fechar", null)
        .setOnDismissListener { tone.stop() }
        .create()
    d.window?.let { w ->
        w.setGravity(Gravity.BOTTOM)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
    d.show()
}
