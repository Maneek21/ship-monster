package dev.shipmonster.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Rules
import kotlin.math.PI
import kotlin.math.sin

/** The den: sky, ground and the party, gently animated. Tap a monster to poke it. */
class DenView(c: Context) : View(c) {
    private var g: Game? = null
    private var now = 0L
    private var tz = 0L
    private var joyUntil = 0L
    private var joyId = -1
    var onPoke: ((Int) -> Unit)? = null

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxes = ArrayList<Pair<Int, Rect>>()
    private val dst = Rect()
    private val clip = Path()

    fun set(g: Game, now: Long, tz: Long) {
        this.g = g
        this.now = now
        this.tz = tz
        val names = g.partyMonsters().joinToString(", ") { it.title }
        contentDescription = "den ${Engine.mood(g).name.lowercase()}" + (if (names.isNotEmpty()) ": $names" else if (g.starter != null) ": egg" else "")
        invalidate()
    }

    /** Everyone (id -1) or one monster looks delighted for a moment. */
    fun joy(id: Int = -1) {
        joyId = id
        joyUntil = System.currentTimeMillis() + 2500
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        val r = Ui.dp(context, 18).toFloat()
        clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val g = g ?: return
        val w = width
        val h = height
        val real = System.currentTimeMillis()
        val mood = Engine.mood(g)
        val hour = Rules.localHour(now, tz)
        val night = hour >= 20 || hour < 5
        val asleep = Engine.hibernating(g, now) || hour >= 23 || hour < 5
        canvas.save()
        canvas.clipPath(clip)

        // sky
        val sky = when {
            mood == Mood.FERAL -> 0xFF4A1028.toInt()
            night -> 0xFF1B2250.toInt()
            hour < 8 -> 0xFFFFC9A0.toInt()
            hour >= 17 -> 0xFFF2A07A.toInt()
            else -> 0xFF8FD3FF.toInt()
        }
        canvas.drawColor(sky)
        val unit = maxOf(2, h / 80)
        if (night || mood == Mood.FERAL) {
            fill.color = if (mood == Mood.FERAL) 0x66FF3B4E else 0xFFFFF0A0.toInt()
            for (i in 0 until 26) {
                val x = ((i * 7919L + 13) % 1000) / 1000f * w
                val y = ((i * 104729L + 7) % 1000) / 1000f * h * 0.55f
                val tw = if (((real / 600) + i) % 5 == 0L) 2 else 1
                canvas.drawRect(x, y, x + unit * tw, y + unit * tw, fill)
            }
        }
        // sun or moon
        fill.color = if (mood == Mood.FERAL) 0xFFFF3B4E.toInt() else if (night) 0xFFFFF8E7.toInt() else 0xFFFFE07A.toInt()
        val sunR = h * 0.09f
        canvas.drawCircle(w * 0.84f, h * 0.2f, sunR, fill)
        if (night && mood != Mood.FERAL) {
            fill.color = sky
            canvas.drawCircle(w * 0.84f + sunR * 0.45f, h * 0.2f - sunR * 0.25f, sunR * 0.85f, fill)
        }
        // far hills
        fill.color = when { mood == Mood.FERAL -> 0xFF341026.toInt(); night -> 0xFF2A2F6B.toInt(); else -> 0xFF7CCB7A.toInt() }
        canvas.drawCircle(w * 0.2f, h * 0.98f, h * 0.42f, fill)
        canvas.drawCircle(w * 0.75f, h * 1.05f, h * 0.5f, fill)
        // ground
        val groundY = (h * 0.74f).toInt()
        fill.color = when { mood == Mood.FERAL -> 0xFF2A0A1E.toInt(); night -> 0xFF23305A.toInt(); else -> 0xFF5DB55B.toInt() }
        canvas.drawRect(0f, groundY.toFloat(), w.toFloat(), h.toFloat(), fill)
        fill.color = when { mood == Mood.FERAL -> 0xFF3A1230.toInt(); night -> 0xFF2D3D70.toInt(); else -> 0xFF4C9F4A.toInt() }
        for (i in 0 until 14) {
            val x = ((i * 6271L + 31) % 1000) / 1000f * w
            val y = groundY + ((i * 3571L) % 100) / 100f * (h - groundY - unit * 2)
            canvas.drawRect(x, y, x + unit * 3, y + unit, fill)
        }

        boxes.clear()
        val party = g.partyMonsters()
        if (party.isEmpty()) {
            val egg = g.starter
            if (egg != null) {
                val px = maxOf(2, (h * 0.5f / Art.CELL).toInt())
                val size = Art.CELL * px
                val wobble = (sin(real / 260.0) * px * (if (real / 2600 % 2 == 0L) 1.5 else 0.0)).toInt()
                dst.set(w / 2 - size / 2 + wobble, groundY - size + px * 4, w / 2 + size / 2 + wobble, groundY + px * 4)
                canvas.drawBitmap(Art.egg(context, egg, 0), null, dst, paint)
            }
        } else {
            val n = party.size
            // lead in the middle, the rest fanned out behind it
            val order = when (n) { 1 -> intArrayOf(0); 2 -> intArrayOf(1, 0); 3 -> intArrayOf(1, 0, 2); 4 -> intArrayOf(3, 1, 0, 2); else -> intArrayOf(3, 1, 0, 2, 4) }
            val slotW = w / (n + 0.4f)
            val draws = ArrayList<Triple<Int, Int, Int>>()   // party index, centre x, base y
            for ((slot, idx) in order.withIndex()) {
                val cx = (w / 2f + (slot - (n - 1) / 2f) * slotW).toInt()
                val back = if (idx == 0) 0 else (h * 0.06f).toInt()
                draws.add(Triple(idx, cx, groundY + (h * 0.12f).toInt() - back))
            }
            for ((idx, cx, baseY) in draws.sortedBy { it.third }) {
                val m = party[idx]
                val lead = idx == 0
                val basePx = h * (if (lead) 0.62f else 0.46f) / Art.CELL * (0.62f + 0.38f * m.stage / 8f)
                val px = maxOf(2, minOf(basePx.toInt(), (slotW * 1.25f / Art.CELL).toInt()))
                val size = Art.CELL * px
                val phase = m.id * 0.9
                val happy = real < joyUntil && (joyId == -1 || joyId == m.id)
                var variant = Art.variantFor(g, m, asleep)
                if (happy) variant = Art.HAPPY
                else if (variant == Art.IDLE && (real + m.id * 700L) % 3600 < 160) variant = Art.BLINK
                val bob = when {
                    happy -> (-kotlin.math.abs(sin(real / 110.0 + phase)) * px * 3).toInt()
                    variant == Art.SLEEP -> 0
                    variant == Art.FERAL -> (sin(real / 60.0 + phase) * px * 0.6).toInt()
                    else -> if (sin(real * 2 * PI / 1500.0 + phase) > 0) -px else 0
                }
                // shadow
                fill.color = 0x33000000
                canvas.drawOval(cx - size * 0.3f, baseY - px * 3.5f, cx + size * 0.3f, baseY - px * 1f, fill)
                dst.set(cx - size / 2, baseY - size + bob, cx + size / 2, baseY + bob)
                canvas.drawBitmap(Art.frame(context, m.species, m.stage, variant), null, dst, paint)
                boxes.add(m.id to Rect(cx - size / 3, baseY - size, cx + size / 3, baseY))
            }
        }
        canvas.restore()
        postInvalidateDelayed(90)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            val hit = boxes.lastOrNull { it.second.contains(e.x.toInt(), e.y.toInt()) } ?: boxes.firstOrNull { g?.party?.firstOrNull() == it.first }
            if (hit != null) {
                joy(hit.first)
                onPoke?.invoke(hit.first)
            }
            performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
