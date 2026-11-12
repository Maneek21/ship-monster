package dev.shipmonster.app

import android.graphics.Bitmap

enum class Mood { HAPPY, JOY, HUNGRY, SICK }

/** Turns the pixel grids into bitmaps. */
object Sprites {
    private const val N = 16

    private fun paint(px: IntArray, grid: Array<String>, pal: Map<Char, Int>) {
        for (y in 0 until N) {
            val row = grid[y]
            for (x in 0 until N) {
                val ch = row[x]
                if (ch != '.') px[y * N + x] = pal[ch] ?: 0
            }
        }
    }

    private fun face(px: IntArray, mood: Mood) {
        val pal = SpriteData.PALETTE
        val ink = pal.getValue('o')
        val pink = pal.getValue('p')
        val ex1 = 5
        val ex2 = 10
        val ey = 7
        val my = 10
        fun dot(x: Int, y: Int, c: Int) {
            if (x in 0 until N && y in 0 until N) px[y * N + x] = c
        }
        if (mood == Mood.HAPPY || mood == Mood.JOY) {
            dot(ex1 - 1, ey + 2, pink)
            dot(ex2 + 1, ey + 2, pink)
        }
        when (mood) {
            Mood.JOY -> {
                dot(ex1 - 1, ey + 1, ink); dot(ex1, ey, ink); dot(ex1 + 1, ey + 1, ink)
                dot(ex2 - 1, ey + 1, ink); dot(ex2, ey, ink); dot(ex2 + 1, ey + 1, ink)
                dot(6, my, ink); dot(7, my, ink); dot(8, my, ink); dot(9, my, ink)
                dot(7, my + 1, pink); dot(8, my + 1, pink)
                dot(1, 3, pal.getValue('c')); dot(14, 5, pal.getValue('c')); dot(13, 1, pal.getValue('w'))
            }
            Mood.HUNGRY -> {
                dot(ex1 + 1, ey, ink); dot(ex1, ey + 1, ink); dot(ex1 - 1, ey + 1, ink)
                dot(ex2 - 1, ey, ink); dot(ex2, ey + 1, ink); dot(ex2 + 1, ey + 1, ink)
                dot(7, my, ink); dot(8, my, ink); dot(6, my + 1, ink); dot(9, my + 1, ink)
                dot(14, 2, pal.getValue('b')); dot(14, 3, pal.getValue('b'))
            }
            Mood.SICK -> {
                dot(ex1 - 1, ey + 1, ink); dot(ex1, ey + 1, ink)
                dot(ex2, ey + 1, ink); dot(ex2 + 1, ey + 1, ink)
                dot(6, my, ink); dot(7, my + 1, ink); dot(8, my, ink); dot(9, my + 1, ink)
            }
            Mood.HAPPY -> {
                dot(ex1, ey, ink); dot(ex1, ey + 1, ink)
                dot(ex2, ey, ink); dot(ex2, ey + 1, ink)
                dot(6, my, ink); dot(7, my + 1, ink); dot(8, my + 1, ink); dot(9, my, ink)
            }
        }
    }

    private fun scaled(px: IntArray, scale: Int): Bitmap {
        val small = Bitmap.createBitmap(px, N, N, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, N * scale, N * scale, false)
    }

    /** The sprite for the pet's current state. joy shows the fed face for a moment after a ship. */
    fun forState(s: PetState, now: Long, scale: Int, joy: Boolean = false): Bitmap {
        val px = IntArray(N * N)
        when (PetEngine.status(s, now)) {
            Status.EGG -> paint(px, SpriteData.EGG, SpriteData.PALETTE)
            Status.DEAD -> paint(px, SpriteData.TOMB, SpriteData.PALETTE)
            Status.MONSTER -> paint(px, SpriteData.MONSTER, SpriteData.PALETTE)
            Status.HAPPY, Status.HUNGRY -> {
                val hungry = PetEngine.status(s, now) == Status.HUNGRY
                val mood = when {
                    joy -> Mood.JOY
                    hungry -> Mood.HUNGRY
                    s.pet.ciFailing -> Mood.SICK
                    else -> Mood.HAPPY
                }
                val grid = when (s.pet.form) {
                    Form.BLOOM -> SpriteData.BLOOM
                    Form.LEAFLING -> SpriteData.LEAFLING
                    else -> SpriteData.SPROUT
                }
                val pal = if (mood == Mood.SICK) SpriteData.PALETTE + SpriteData.SICK else SpriteData.PALETTE
                paint(px, grid, pal)
                face(px, mood)
            }
        }
        return scaled(px, scale)
    }
}
