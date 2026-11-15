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
