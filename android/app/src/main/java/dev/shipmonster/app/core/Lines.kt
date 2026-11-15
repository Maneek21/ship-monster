package dev.shipmonster.app.core

import dev.shipmonster.app.core.Rules.H

class Line(val situation: String, val voice: Voice?, val conds: List<String>, val text: String)

/** Pre-written monster dialogue. Loaded from assets/lines.tsv; chosen offline, deterministically. */
class LineBank(tsv: String) {
    val lines: List<Line> = tsv.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { row ->
            val p = row.split("|", limit = 4)
            if (p.size < 4) return@mapNotNull null
            val voice = if (p[1] == "*") null else Voice.valueOf(p[1].uppercase())
            val conds = if (p[2] == "*") emptyList() else p[2].split(",").map { it.trim() }
            Line(p[0], voice, conds, p[3])
        }.toList()

    private val bySituation = lines.groupBy { it.situation }

    fun has(situation: String) = bySituation.containsKey(situation)

    private fun holds(c: String, stage: Int, species: String, stars: Int, streak: Int): Boolean = when {
        c == "tiny" -> stage <= 2
        c == "mid" -> stage in 3..5
        c == "giant" -> stage >= 6
        c.startsWith("streak>=") -> streak >= c.substring(8).toInt()
        c.startsWith("streak<") -> streak < c.substring(7).toInt()
        c.startsWith("stage=") -> stage == c.substring(6).toInt()
        c.startsWith("stars>=") -> stars >= c.substring(7).toInt()
        c.startsWith("species=") -> species == c.substring(8)
        else -> false
    }

    /**
     * The lines that fit, laid out as a cycle. Lines written for this voice or this situation in
     * particular come round twice as often as the generic ones, and no line sits next to itself.
     */
    private fun candidates(situation: String, voice: Voice?, stage: Int, species: String, stars: Int, streak: Int): List<Line> {
        val fits = (bySituation[situation] ?: return emptyList()).filter { l ->
            (l.voice == null || l.voice == voice) && l.conds.all { holds(it, stage, species, stars, streak) }
        }
        val order = fits.sortedBy { mix(it.text.hashCode().toLong()) }
        val special = order.filter { it.voice != null || it.conds.isNotEmpty() }
        val generic = order.filter { it.voice == null && it.conds.isEmpty() }
        return if (special.size >= 2) special + generic + special else special + generic
    }

    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return (z xor (z ushr 31)) and Long.MAX_VALUE
    }

    /**
     * The line [m] says in [situation]. The same seed always gives the same line, so the home screen
     * and the widget agree; the next seed always gives a different line.
     */
    fun say(situation: String, m: Monster?, g: Game, seed: Long, app: String = ""): String {
        val stage = m?.stage ?: 1
        val c = candidates(situation, m?.sp?.voice, stage, m?.species ?: "", m?.stars ?: 0, g.streak)
        if (c.isEmpty()) return "..."
        val line = c[Math.floorMod(seed, c.size.toLong()).toInt()]
        var text = line.text
            .replace("{name}", m?.name ?: "")
            .replace("{streak}", g.streak.toString())
            .replace("{hours}", (g.idleMs / H).toString())
            .replace("{next}", Engine.hoursToNext(g).toString())
            .replace("{chow}", g.pantry.toString())
            .replace("{stage}", m?.stageName ?: "")
            .replace("{app}", if (app.isEmpty()) "that app" else app)
        if (m != null) text = if (stage <= 2) text.lowercase() else if (stage >= 6) text.uppercase() else text
        return text
    }

    /** Which idle situation the lead monster is in right now. Changes every 20 minutes at most. */
    fun idleSituation(g: Game, now: Long, tz: Long): String {
        val m = g.lead ?: return "egg"
        when (Engine.mood(g)) {
            Mood.FERAL -> return "feral"
            Mood.RESTLESS -> return "restless"
            else -> {}
        }
        if (Engine.hibernating(g, now)) return "hibernate"
        if (m.belly <= 0.0) return "hungry"
        val pool = ArrayList<String>()
        pool.add("fed"); pool.add("fed")
        if (Engine.isRest(g, now, tz)) { pool.add("restday"); pool.add("restday") }
        if (m.belly < 30) { pool.add("hungry"); pool.add("hungry") }
        if (g.pantry < g.partyMonsters().sumOf { it.sp.rarity.appetite }) pool.add("pantrylow")
        if (g.ciRed) pool.add("cired")
        val hour = Rules.localHour(now, tz)
        if (hour >= 23 || hour < 5) pool.add("night")
        if (hour in 5..9) pool.add("morning")
        if (g.streak >= 7) pool.add("streak")
        return pool[(mix(now / (20 * 60_000L)) % pool.size).toInt()]
    }

    fun idle(g: Game, now: Long, tz: Long): String {
        val seed = now / (20 * 60_000L) * 131 + (g.lead?.id ?: 0)
        return say(idleSituation(g, now, tz), g.lead, g, seed)
    }
}
