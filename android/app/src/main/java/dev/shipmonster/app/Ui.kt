package dev.shipmonster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for building screens in code. */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, start: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; marginStart = start }

    fun col(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(c: Context, s: CharSequence, size: Float = 14f, color: Int = R.color.ink, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(c.getColor(color))
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(dp(c, 2).toFloat(), 1f)
        }

    fun h(c: Context, s: CharSequence, color: Int = R.color.ink): TextView = text(c, s, 16f, color, bold = true, mono = true)
    fun body(c: Context, s: CharSequence, color: Int = R.color.ink_soft): TextView = text(c, s, 14f, color)

    /** A cream card. Add it to a vertical parent; it brings its own top margin. */
    fun card(c: Context): LinearLayout = col(c).apply {
        setBackgroundResource(R.drawable.card)
        val p = dp(c, 14)
        setPadding(p, p, p, p)
        layoutParams = lp(MATCH, WRAP, top = dp(c, 12))
    }

    const val PRIMARY = 0
    const val LIGHT = 1
    const val OUTLINE = 2
    const val DANGER = 3

    fun button(c: Context, label: CharSequence, kind: Int = PRIMARY, onClick: () -> Unit): Button {
        val style = when (kind) { LIGHT -> R.style.Btn_Light; OUTLINE -> R.style.Btn_Outline; DANGER -> R.style.Btn_Danger; else -> R.style.Btn }
        return Button(c, null, 0, style).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = lp(MATCH, WRAP, top = dp(c, 10))
        }
    }

    /** A crisp pixel-art image at any size. */
    fun sprite(c: Context, b: Bitmap, sizeDp: Int): ImageView = ImageView(c).apply {
        setImageDrawable(pixels(c, b))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
    }

    fun pixels(c: Context, b: Bitmap): BitmapDrawable = BitmapDrawable(c.resources, b).apply {
        isFilterBitmap = false
        setAntiAlias(false)
    }

    fun space(c: Context, hDp: Int): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp)) }

    fun bar(c: Context, value: Float, color: Int, marks: FloatArray = FloatArray(0)): BarView = BarView(c).apply {
        this.value = value
        this.color = c.getColor(color)
        this.marks = marks
        layoutParams = lp(MATCH, dp(c, 14), top = dp(c, 6))
    }
}

/** A chunky progress bar with an ink outline. */
class BarView(c: Context) : View(c) {
    var value = 0f
    var color = 0
    var marks = FloatArray(0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = Ui.dp(context, 2).toFloat()
        val rad = h / 2
        p.style = Paint.Style.FILL
        p.color = 0xFFE9DFC8.toInt()
        r.set(s / 2, s / 2, w - s / 2, h - s / 2)
        canvas.drawRoundRect(r, rad, rad, p)
        val v = value.coerceIn(0f, 1f)
        if (v > 0f) {
            p.color = color
            canvas.save()
            canvas.clipRect(0f, 0f, maxOf(h, w * v), h)
            canvas.drawRoundRect(r, rad, rad, p)
            canvas.restore()
        }
        p.color = context.getColor(R.color.line)
        for (m in marks) canvas.drawRect(w * m - s / 2, s, w * m + s / 2, h - s, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s
        canvas.drawRoundRect(r, rad, rad, p)
    }
}
