package dev.shipmonster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Monster
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Dex

/** Sprite sheets from assets/sprites. Every frame is a 32x32 cell; see art/generate.py. */
object Art {
    const val CELL = 32
    const val IDLE = 0
    const val BLINK = 1
    const val HAPPY = 2
    const val HUNGRY = 3
    const val FERAL = 4
    const val SLEEP = 5
    const val STONE = 6

    val ICONS = listOf("dawn", "day", "dusk", "midnight", "gem", "flame", "chow")

    private val sheets = HashMap<String, Bitmap>()
    private val frames = HashMap<String, Bitmap>()

    private fun sheet(c: Context, name: String): Bitmap? {
        sheets[name]?.let { return it }
        return try {
            val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            c.applicationContext.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it, null, o) }
                ?.also { sheets[name] = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun cell(c: Context, name: String, col: Int, row: Int): Bitmap {
        val key = "$name/$col/$row"
        frames[key]?.let { return it }
        val s = sheet(c, name)
        val b = if (s != null && (col + 1) * CELL <= s.width && (row + 1) * CELL <= s.height) {
            Bitmap.createBitmap(s, col * CELL, row * CELL, CELL, CELL)
        } else {
            Bitmap.createBitmap(CELL, CELL, Bitmap.Config.ARGB_8888)
        }
        frames[key] = b
        return b
    }

    fun frame(c: Context, species: String, stage: Int, variant: Int): Bitmap =
        cell(c, species, variant, (stage - 1).coerceIn(0, 7))

    fun egg(c: Context, species: String, cracks: Int): Bitmap =
        cell(c, "eggs", cracks.coerceIn(0, 3), Dex.get(species).rarity.ordinal)

    fun icon(c: Context, name: String): Bitmap = cell(c, "icons", ICONS.indexOf(name).coerceAtLeast(0), 0)

    /** The frame that shows how this monster feels right now, ignoring animation. */
    fun variantFor(g: Game, m: Monster, sleeping: Boolean): Int = when {
        m.stone -> STONE
        m.id in g.party && Engine.mood(g) == Mood.FERAL -> FERAL
        sleeping -> SLEEP
        m.id in g.party && m.belly < 30 -> HUNGRY
        else -> IDLE
    }

    private val portraits = HashMap<String, Bitmap>()

    /**
     * A frame cropped to the monster and set on a square canvas, bottom centre. With [grow] the
     * monster fills more of the square the bigger its stage, so a Speck still looks small next to a Titan.
     */
    fun portrait(c: Context, species: String, stage: Int, variant: Int, grow: Boolean = true): Bitmap {
        val key = "$species/$stage/$variant/$grow"
        portraits[key]?.let { return it }
        val f = frame(c, species, stage, variant)
        var l = CELL; var t = CELL; var r = -1; var b = -1
        for (y in 0 until CELL) for (x in 0 until CELL) if ((f.getPixel(x, y) ushr 24) != 0) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        if (r < 0) return f
        val w = r - l + 1
        val h = b - t + 1
        val fill = if (grow) 0.5f + 0.5f * stage / 8f else 1f
        val side = maxOf(4, Math.ceil((maxOf(w, h) / fill).toDouble()).toInt())
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(f, android.graphics.Rect(l, t, r + 1, b + 1),
            android.graphics.Rect((side - w) / 2, side - h, (side - w) / 2 + w, side), Paint().apply { isFilterBitmap = false })
        portraits[key] = out
        return out
    }

    /** A portrait blown up with hard pixel edges to roughly [px] wide, for widgets and share images. */
    fun big(c: Context, species: String, stage: Int, variant: Int, px: Int): Bitmap {
        val p = portrait(c, species, stage, variant)
        return scaled(p, maxOf(1, px / p.width))
    }

    fun scaled(b: Bitmap, scale: Int): Bitmap = Bitmap.createScaledBitmap(b, b.width * scale, b.height * scale, false)

    /** A solid-colour cut-out, for monsters not met yet. */
    fun silhouette(b: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        Canvas(out).drawBitmap(b, 0f, 0f, p)
        return out
    }
}

object Fmt {
    fun hours(h: Int): String = if (h >= 48) "${h / 24}d ${h % 24}h" else "${h}h"

    fun ago(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 48 * 60 -> "${m / 60}h ago"
            else -> "${m / 1440}d ago"
        }
    }
}
