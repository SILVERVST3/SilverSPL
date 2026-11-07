package br.silver.spl.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Fábrica de componentes visuais no estilo SILVER (sem XML). */
class Ui(val ctx: Context) {
    fun dp(v: Int) = ctx.dpi(v)

    fun text(s: String, sp: Float = 14f, color: Int = Pal.TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun vbox(padH: Int = 0, padV: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    fun cardBg(color: Int = Pal.CARD, radius: Int = 14): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Pal.STROKE)
    }

    fun card(): LinearLayout = vbox(10, 8).apply { background = cardBg() }

    fun silverBg(pressed: Boolean = false): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (pressed) intArrayOf(Pal.SILVER_LO, Pal.SILVER) else intArrayOf(Pal.SILVER_HI, Pal.SILVER, Pal.SILVER_LO)
    ).apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.rgb(0x55, 0x5C, 0x66)) }

    fun button(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(0x15, 0x18, 0x1C)); gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = silverBg()
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.background = silverBg(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.background = silverBg()
            }
            false
        }
    }

    fun darkButton(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label; textSize = 13f; setTextColor(Pal.TEXT); gravity = Gravity.CENTER
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = cardBg(Pal.CARD2, 10)
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun edit(hint: String, value: String = ""): EditText = EditText(ctx).apply {
        this.hint = hint; setText(value); setTextColor(Pal.TEXT); setHintTextColor(Pal.TEXT_DIM)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        textSize = 16f
        setSingleLine()
    }

    fun section(title: String): TextView = text(title, 15f, Pal.SILVER_HI, true).apply {
        setPadding(0, dp(14), 0, dp(4))
    }

    fun spacer(wDp: Int = 0, hDp: Int = 0): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
    }

    fun weightSpacer(): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           ml: Int = 0, mt: Int = 0, mr: Int = 0, mb: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(ml), dp(mt), dp(mr), dp(mb)) }
}

/** Grupo de botões segmentados (seleção única). */
class Segmented(ctx: Context, labels: List<String>, selected: Int, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private val items = ArrayList<TextView>()
    var selectedIndex = selected
        private set

    init {
        orientation = HORIZONTAL
        val ui = Ui(ctx)
        background = ui.cardBg(Pal.CARD2, 10)
        setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        labels.forEachIndexed { i, s ->
            val t = TextView(ctx).apply {
                text = s; textSize = 13f; gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                setOnClickListener { select(i, true) }
            }
            items.add(t)
            addView(t, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        refresh()
    }

    fun select(i: Int, notify: Boolean) {
        selectedIndex = i
        refresh()
        if (notify) onSelect(i)
    }

    private fun refresh() {
        val ui = Ui(context)
        items.forEachIndexed { i, t ->
            if (i == selectedIndex) {
                t.background = ui.silverBg(); t.setTextColor(Color.rgb(0x15, 0x18, 0x1C)); t.typeface = Typeface.DEFAULT_BOLD
            } else {
                t.background = null; t.setTextColor(Pal.TEXT_DIM); t.typeface = Typeface.DEFAULT
            }
        }
    }
}
