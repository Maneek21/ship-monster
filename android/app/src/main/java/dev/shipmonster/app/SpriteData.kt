package dev.shipmonster.app

/*
 * 16x16 pixel grids, one character per pixel. '.' is transparent.
 * Kept free of Android imports so the grids can be checked in unit tests.
 * Mirrors simulator/sprites.js.
 */
object SpriteData {
    val SPROUT = arrayOf(
        "................",
        "................",
        "........gG......",
        ".......gg.......",
        ".......G........",
        "....oooooooo....",
        "...oyyyyyyyyo...",
        "..oyyyyyyyyyyo..",
        "..oyyyyyyyyyyo..",
        "..oyyyyyyyyyyo..",
        "..oyyyyyyyyyyo..",
        "..oYyyyyyyyyYo..",
        "...oYYYYYYYYo...",
        "....oooooooo....",
        "....oo....oo....",
        "................"
    )

    val LEAFLING = arrayOf(
        ".....gG..Gg.....",
        "......gGGg......",
        ".......GG.......",
        "...oooooooooo...",
        "..oyyyyyyyyyyo..",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        "ooyyyyyyyyyyyyoo",
        ".oyyyyyyyyyyyyo.",
        ".oYyyyyyyyyyyYo.",
        "..oYYYYYYYYYYo..",
        "...oooooooooo...",
        "...ooo....ooo...",
        "................"
    )

    val BLOOM = arrayOf(
        "......ffff......",
        ".....ffccff.....",
        "......fGGf......",
        "...oooooooooo...",
        "..oyyyyyyyyyyo..",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        ".oyyyyyyyyyyyyo.",
        "ooyyyyyyyyyyyyoo",
        ".oyyyyyyyyyyyyo.",
        ".oYyyyyyyyyyyYo.",
        "..oYYYYYYYYYYo..",
        "...oooooooooo...",
        "...ooo....ooo...",
        "................"
    )

    val EGG = arrayOf(
        "................",
        "................",
        "......oooo......",
        ".....oeeeeo.....",
        "....oeeeeeeo....",
        "....oeeyeeeo....",
        "...oeeeeeeeeo...",
        "...oeeeeeyyeo...",
        "...oeyeeeyyeo...",
        "...oeeeeeeeeo...",
        "...oeeeeeeeeo...",
        "...oEeeeeeeEo...",
        "....oEEEEEEo....",
        ".....oooooo.....",
        "................",
        "................"
    )

    val MONSTER = arrayOf(
        "..h.......h.....",
        "..hh.....hh.....",
        "..ooooooooo.....",
        ".ommmmmmmmmo....",
        ".ommmmmmmmmo.s.s",
        ".omrrmmmrrmo.s.s",
        ".omrommmormo.sss",
        ".ommmmmmmmmo..s.",
        ".owowowowowo..s.",
        ".ooooooooooomms.",
        ".oowowowowoo..s.",
        ".ommmmmmmmmo..s.",
        ".oMMMMMMMMMo....",
        "..ooooooooo.....",
        "..ooo...ooo.....",
        "................"
    )

    val TOMB = arrayOf(
        "................",
        "...oooooooooo...",
        "..otttttttttto..",
        ".otttttttttttto.",
        ".ottTTTtTtTTTto.",
        ".ottTtTtTtTtTto.",
        ".ottTTttTtTTTto.",
        ".ottTtTtTtTttto.",
        ".ottTtTtTtTttto.",
        ".otttttttttttto.",
        ".otttttttttttto.",
        ".oTTTTTTTTTTTTo.",
        ".oooooooooooooo.",
        "gGggGgGGgGggGgGg",
        "................",
        "................"
    )

    val ALL: Map<String, Array<String>> = mapOf(
        "sprout" to SPROUT, "leafling" to LEAFLING, "bloom" to BLOOM,
        "egg" to EGG, "monster" to MONSTER, "tomb" to TOMB
    )

    /** ARGB colours keyed by grid character. */
    val PALETTE: Map<Char, Int> = mapOf(
        'o' to 0xFF2A1F3D.toInt(), 'y' to 0xFFFFD447.toInt(), 'Y' to 0xFFF0A31F.toInt(),
        'p' to 0xFFFF8FA3.toInt(), 'g' to 0xFF5FD07A.toInt(), 'G' to 0xFF2F9550.toInt(),
        'f' to 0xFFFF7EB6.toInt(), 'c' to 0xFFFFF0A0.toInt(), 'b' to 0xFF6EC3FF.toInt(),
        'w' to 0xFFFFFAF0.toInt(), 'e' to 0xFFFFF3D6.toInt(), 'E' to 0xFFECD3A0.toInt(),
        'm' to 0xFF9A5CFF.toInt(), 'M' to 0xFF6A35D6.toInt(), 'r' to 0xFFFF3B4E.toInt(),
        'h' to 0xFFFFE2A0.toInt(), 's' to 0xFFD7DBE3.toInt(), 't' to 0xFFB4BAC6.toInt(),
        'T' to 0xFF7B8394.toInt()
    )

    /** Body colours swapped in when CI is failing. */
    val SICK: Map<Char, Int> = mapOf('y' to 0xFFCFE59A.toInt(), 'Y' to 0xFF9CC266.toInt())
}
