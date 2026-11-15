package dev.shipmonster.app.core

import dev.shipmonster.app.core.Rules.D
import dev.shipmonster.app.core.Rules.H

enum class Ev { FED, MERGED, HATCHED, EVOLVED, DROPPED, RESTLESS, WARNING, FERAL, CALMED, FALL, BENCHED, LEVEL, MILESTONE, BOUNTY, GEM }

class Event(val kind: Ev, val monster: Monster? = null, val n: Int = 0, val text: String = "")

class Hatch(val monster: Monster, val isNew: Boolean, val star: Boolean, val revived: Boolean, val chow: Int)

enum class Mood { EMPTY, CALM, RESTLESS, FERAL }

/** Every rule of the game. Pure: no Android, no clock, no I/O. See docs/GAME.md. */
object Engine {

    // ------------------------------------------------------------ reading the state

    fun mood(g: Game): Mood = when {
        g.party.isEmpty() -> Mood.EMPTY
        g.feral -> Mood.FERAL
        g.idleMs >= Rules.RESTLESS_H * H -> Mood.RESTLESS
        else -> Mood.CALM
    }

    /** The lock holds while the horde is feral, except during a truce and on rest days. */
    fun lockActive(g: Game, now: Long, tz: Long): Boolean =
        mood(g) == Mood.FERAL && now >= g.truceUntil && !isRest(g, now, tz)

    fun hibernating(g: Game, t: Long): Boolean = t >= g.hibernateFrom && t < g.hibernateUntil

    private fun restDay(g: Game, day: Long): Boolean =
        Rules.dow(day) in (if (day <= g.restChangedDay) g.restPrev else g.restDays)

    fun isRest(g: Game, t: Long, tz: Long): Boolean = hibernating(g, t) || restDay(g, Rules.localDay(t, tz))

    /** Changes a rest day. Today keeps the old rule, so the clock cannot be paused on the spot. */
    fun setRestDay(g: Game, dow: Int, on: Boolean, now: Long, tz: Long): Boolean {
        if (dow !in 0..6 || on == (dow in g.restDays)) return false
        if (on && g.restDays.size >= Rules.MAX_REST_DAYS) return false
        val today = Rules.localDay(now, tz)
        if (g.restChangedDay != today) {
            g.restPrev.clear()
            g.restPrev.addAll(g.restDays)
            g.restChangedDay = today
        }
        if (on) g.restDays.add(dow) else g.restDays.remove(dow)
        return true
    }

    /** Awake time between two moments: rest days and hibernation do not count. */
    fun awakeBetween(g: Game, from: Long, to: Long, tz: Long): Long {
        var t = from
        var sum = 0L
        var guard = 0
        while (t < to && guard++ < 500) {
            var end = minOf(to, (Rules.localDay(t, tz) + 1) * D - tz)
            if (g.hibernateUntil > t && g.hibernateUntil < end) end = g.hibernateUntil
            if (g.hibernateFrom > t && g.hibernateFrom < end) end = g.hibernateFrom
            if (!isRest(g, t, tz)) sum += end - t
            t = end
        }
        return sum
    }

    /** The real moment the idle clock will reach [hours], counted from the last tick. Long.MAX_VALUE if never. */
    fun whenIdle(g: Game, hours: Int, tz: Long): Long {
        if (g.party.isEmpty()) return Long.MAX_VALUE
        var rem = hours * H - g.idleMs
        var t = g.lastTick
        if (rem <= 0) return t
        var guard = 0
        while (guard++ < 200) {
            var end = (Rules.localDay(t, tz) + 1) * D - tz
            if (g.hibernateUntil > t && g.hibernateUntil < end) end = g.hibernateUntil
            if (g.hibernateFrom > t && g.hibernateFrom < end) end = g.hibernateFrom
            if (!isRest(g, t, tz)) {
                if (rem <= end - t) return t + rem
                rem -= end - t
            }
            t = end
        }
        return Long.MAX_VALUE
    }

    /** The real moment of the next change for the worse: restless, feral or the fall. */
    fun deadline(g: Game, tz: Long): Long = when (mood(g)) {
        Mood.EMPTY -> Long.MAX_VALUE
        Mood.CALM -> whenIdle(g, Rules.RESTLESS_H, tz)
        Mood.RESTLESS -> whenIdle(g, Rules.FERAL_H, tz)
        Mood.FERAL -> whenIdle(g, Rules.FALL_H, tz)
    }

    /** The real moment of the next change that cannot be taken back: going feral, or the fall. */
    fun pointOfNoReturn(g: Game, tz: Long): Long =
        if (g.party.isEmpty()) Long.MAX_VALUE else whenIdle(g, if (g.feral) Rules.FALL_H else Rules.FERAL_H, tz)

    /** The streak as it stands right now: it is already gone once a working day has passed without a ship. */
    fun liveStreak(g: Game, now: Long, tz: Long): Int =
        if (g.streak > 0 && g.lastShipDay != Long.MIN_VALUE && !gapIsRest(g, g.lastShipDay, Rules.localDay(now, tz) , tz)) 0 else g.streak

    /** Awake hours until the next change of state. 0 when there is no party. */
    fun hoursToNext(g: Game): Int {
        val target = when (mood(g)) {
            Mood.EMPTY -> return 0
            Mood.CALM -> Rules.RESTLESS_H
            Mood.RESTLESS -> Rules.FERAL_H
            Mood.FERAL -> Rules.FALL_H
        }
        return (((target * H - g.idleMs) + H - 1) / H).toInt().coerceAtLeast(0)
    }

    /** 0..1 how far the horde is from calm (0) to the fall (1). */
    fun danger(g: Game): Float = (g.idleMs.toFloat() / (Rules.FALL_H * H)).coerceIn(0f, 1f)

    /** When the app should next wake itself to re-check. */
    fun nextWakeAt(g: Game, now: Long, tz: Long): Long {
        var best = (Rules.localDay(now, tz) + 1) * D - tz + 60_000
        val next = deadline(g, tz)
        if (next != Long.MAX_VALUE && next + 1000 < best) best = maxOf(now, next) + 1000
        val evening = Rules.localDay(now, tz) * D - tz + 18 * H + 60_000     // the streak reminder
        if (evening > now && evening < best) best = evening
        val warn = whenIdle(g, Rules.WARN_H, tz)
        if (warn > now && warn + 1000 < best) best = warn + 1000
        if (g.truceUntil > now && g.truceUntil + 1000 < best) best = g.truceUntil + 1000
        if (g.hibernateUntil > now && g.hibernateUntil + 1000 < best) best = g.hibernateUntil + 1000
        return best
    }

    // ------------------------------------------------------------ time

    fun advance(g: Game, now: Long, tz: Long): List<Event> {
        val evs = ArrayList<Event>()
        var t = g.lastTick
        if (now <= t) return evs
        if (now - t > 400 * D) t = now - 400 * D
        while (t < now) {
            val day = Rules.localDay(t, tz)
            val midnight = (day + 1) * D - tz
            var end = minOf(now, midnight)
            if (g.hibernateUntil > t && g.hibernateUntil < end) end = g.hibernateUntil
            if (g.hibernateFrom > t && g.hibernateFrom < end) end = g.hibernateFrom
            val awake = !isRest(g, t, tz)
            if (awake) step(g, end - t, end, evs)
            if (end == midnight) endOfDay(g, day, awake, end, evs)
            t = end
        }
        g.lastTick = now
        return evs
    }

    private fun step(g: Game, dt: Long, at: Long, evs: ArrayList<Event>) {
        if (g.party.isEmpty()) return
        val before = g.idleMs
        g.idleMs += dt
        val starved = HashMap<Int, Long>()      // how much of this stretch each belly spent empty
        for (m in g.partyMonsters()) {
            val untilEmpty = (m.belly / 50.0 * D).toLong()
            starved[m.id] = (dt - untilEmpty).coerceIn(0, dt)
            m.belly = (m.belly - 50.0 * dt / D).coerceAtLeast(0.0)
        }
        graze(g)
        if (before < Rules.RESTLESS_H * H && g.idleMs >= Rules.RESTLESS_H * H) {
            g.note(at, "restless", "A day without shipping. The horde is restless.")
            evs.add(Event(Ev.RESTLESS, g.lead))
        }
        if (!g.feral && before < Rules.WARN_H * H && g.idleMs >= Rules.WARN_H * H && g.idleMs < Rules.FERAL_H * H) {
            evs.add(Event(Ev.WARNING, g.lead, Rules.FERAL_H - Rules.WARN_H))
        }
        if (!g.feral && g.idleMs >= Rules.FERAL_H * H) goFeral(g, at, evs)
        if (g.idleMs >= Rules.FALL_H * H) { fall(g, at, evs); return }
        for (m in g.partyMonsters()) {
            if (m.belly <= 0.0) m.emptyMs += starved[m.id] ?: 0L else m.emptyMs = 0
            if (!g.feral && m.emptyMs >= D && g.party.size > 1) {
                g.party.remove(m.id)
                m.emptyMs = 0
                m.belly = 50.0
                g.note(at, "bench", "${m.name} went hungry for a day and sulked off to the den.")
                evs.add(Event(Ev.BENCHED, m))
            }
        }
    }

    private fun endOfDay(g: Game, day: Long, awake: Boolean, at: Long, evs: ArrayList<Event>) {
        if (awake && !g.feral) {
            for (m in g.partyMonsters()) {
                var pts = if (m.belly > 50) 10.0 else if (m.belly > 0) 4.0 else 0.0
                if (pts > 0 && m.ateFav) pts += 2
                // a busy day: one extra point for every ship after the first, up to five
                if (pts > 0 && g.day == day) pts += (g.shipsToday - 1).coerceIn(0, 5)
                if (pts > 0) grow(g, m, pts * (1 + 0.1 * m.stars), at, evs)
            }
        }
        for (m in g.monsters) { m.ateFav = false; m.treats = 0 }
        rollDay(g, day + 1)
    }

    private fun rollDay(g: Game, day: Long) {
        if (day <= g.day) return          // a late event from an earlier day must not reopen that day's allowances
        g.day = day
        g.pushesToday = 0
        g.shipsToday = 0
        g.gemsToday = 0
        for (m in g.monsters) m.treats = 0
    }

    private fun goFeral(g: Game, at: Long, evs: ArrayList<Event>) {
        g.feral = true
        val lost = g.streak
        g.streak = 0
        for (m in g.partyMonsters()) {
            val before = m.stage
            m.gp = (m.gp - maxOf(15.0, m.gp * 0.2)).coerceAtLeast(0.0)
            if (m.stage < before) evs.add(Event(Ev.DROPPED, m, m.stage))
        }
        g.note(at, "feral", "Two days without shipping. The horde went feral, everyone in the party lost a fifth of their growth" +
            (if (lost > 0) " and your $lost-day streak is gone." else "."))
        evs.add(Event(Ev.FERAL, g.lead, lost))
    }

    private fun fall(g: Game, at: Long, evs: ArrayList<Event>) {
        val lead = g.lead
        val party = g.partyMonsters()
        for (m in party) {
            m.stone = true
            m.gp = Math.floor(m.gp / 2)
            m.emptyMs = 0
        }
        g.party.clear()
        g.falls++
        g.feral = false
        g.idleMs = 0
        g.truceUntil = 0
        g.truceUsed = false
        val days = if (lead != null) ((at - lead.bornAt) / D).coerceAtLeast(0) else 0
        val text = "The horde fell. Led by ${lead?.title ?: "nobody"}, hatched $days days ago. " +
            (if (party.size == 1) "It turned to stone" else "All ${party.size} turned to stone") + " and lost half their growth."
        g.hall.add(0, LogEntry(at, "fall", text))
        g.note(at, "fall", text)
        evs.add(Event(Ev.FALL, lead, party.size, text))
        val fresh = g.den().sortedByDescending { it.gp }.take(g.slots)
        for (m in fresh) g.party.add(m.id)
        if (fresh.isNotEmpty()) {
            g.note(at, "lead", fresh.joinToString(", ") { it.name } + " stepped out of the den to carry on.")
        }
    }

    private fun grow(g: Game, m: Monster, pts: Double, at: Long, evs: ArrayList<Event>) {
        val s0 = m.stage
        m.gp += pts
        val s1 = m.stage
        if (s1 > s0) {
            if (s1 > m.peak) m.peak = s1
            g.note(at, "evolve", "${m.name} grew into a ${Rules.STAGE_NAMES[s1]}.")
            if (s1 == 8) g.hall.add(0, LogEntry(at, "titan", "${m.name} ${m.sp.epithet} became a Titan."))
            evs.add(Event(Ev.EVOLVED, m, s1))
        }
    }

    private fun graze(g: Game) {
        for (m in g.partyMonsters()) {
            val per = 50.0 / m.sp.rarity.appetite
            val fav = m.sp.fav.ordinal
            while (m.belly <= 100.0 - per + 1e-9 && g.pantry > 0) {
                var f = fav
                if (g.chow[f] <= 0) { f = 0; for (i in 1..3) if (g.chow[i] > g.chow[f]) f = i }
                g.chow[f]--
                m.belly = (m.belly + per).coerceAtMost(100.0)
                if (f == fav) m.ateFav = true
            }
        }
    }

    private fun addChow(g: Game, n: Int, f: Flavor, at: Long, evs: ArrayList<Event>) {
        val take = n.coerceAtMost((g.pantryCap - g.pantry).coerceAtLeast(0))
        g.chow[f.ordinal] += take
        val over = n - take
        if (over > 0) g.lead?.let { grow(g, it, over / 10.0, at, evs) }
        val before = g.level
        g.lifetimeChow += n
        val after = g.level
        if (after > before) {
            g.gems += after - before
            g.note(at, "level", "Keeper level $after: ${Rules.title(after)}. +${after - before} gem.")
            evs.add(Event(Ev.LEVEL, null, after))
        }
    }

    // ------------------------------------------------------------ shipping

    /** Lets a first sync replay history from before the game was created. */
    fun rewind(g: Game, t: Long) {
        if (g.monsters.isEmpty() && t < g.lastTick) g.lastTick = t
    }

    fun ship(g: Game, at: Long, merge: Boolean, tz: Long, repo: String = ""): List<Event> {
        val evs = ArrayList<Event>()
        if (at > g.lastTick) evs.addAll(advance(g, at, tz))
        val day = Rules.localDay(at, tz)
        rollDay(g, day)
        val flavor = Rules.flavorAt(Rules.localHour(at, tz))
        val where = if (repo.isNotEmpty()) " to ${repo.substringAfterLast('/')}" else ""

        val egg = g.starter
        if (egg != null) {
            g.starter = null
            val have = g.owned(egg)
            val m = if (have == null) spawn(g, egg, at) else have.also {
                // every common is already owned, so the egg wakes its twin
                if (it.stone) wakeStatue(g, it)
                if (it.stars < Rules.MAX_STARS) it.stars++
                if (it.id !in g.party) g.party.add(it.id)
                g.note(at, "hatch", "${it.name} is back.")
            }
            evs.add(Event(Ev.HATCHED, m))
        }
        g.shipsToday++
        if (merge) {
            g.merges++
            var gem = 0
            if (g.gemsToday < Rules.MERGE_GEMS_PER_DAY) { g.gems++; g.gemsToday++; gem = 1 }
            g.note(at, "merge", "Merged a pull request$where. +10 ${flavor.label} chow" + (if (gem > 0) ", +1 gem." else "."))
            addChow(g, 10, flavor, at, evs)
            evs.add(Event(Ev.MERGED, g.lead, gem))
        } else {
            g.pushes++
            g.pushesToday++
            val n = Rules.chowForPush(g.pushesToday)
            g.note(at, "push", "Pushed$where. +$n ${flavor.label} chow.")
            addChow(g, n, flavor, at, evs)
            evs.add(Event(Ev.FED, g.lead, n))
        }

        if (g.lastShipDay == Long.MIN_VALUE || day > g.lastShipDay) {
            g.shipDays++
            if (g.shipDays % Rules.SHIP_DAYS_PER_GEM == 0) {
                g.gems++
                g.note(at, "gem", "Day ${g.shipDays} of shipping. +1 gem.")
                evs.add(Event(Ev.GEM, g.lead, 1))
            }
            val keeps = g.streak > 0 && g.lastShipDay != Long.MIN_VALUE && gapIsRest(g, g.lastShipDay, day, tz)
            g.streak = if (keeps) g.streak + 1 else 1
            if (g.streak > g.bestStreak) g.bestStreak = g.streak
            Rules.MILESTONES[g.streak]?.let { gems ->
                g.gems += gems
                g.note(at, "gem", "${g.streak}-day streak. +$gems gem" + (if (gems > 1) "s." else "."))
                evs.add(Event(Ev.MILESTONE, g.lead, g.streak))
            }
            val w = Rules.week(day)
            if (w != g.week) { g.week = w; g.weekShipDays = 0; g.weekPaid = false }
            g.weekShipDays++
            if (g.weekShipDays >= 5 && !g.weekPaid) {
                g.weekPaid = true
                g.gems++
                g.note(at, "gem", "Weekly bounty: shipped on 5 days this week. +1 gem.")
                evs.add(Event(Ev.BOUNTY, g.lead, 1))
            }
            g.lastShipDay = day
        }

        val wasFeral = g.feral
        // A ship that is only seen later still counts from the moment it happened.
        g.idleMs = if (at >= g.lastTick) 0 else minOf(g.idleMs, awakeBetween(g, at, g.lastTick, tz))
        if (at > g.lastShipAt) g.lastShipAt = at
        if (g.idleMs >= Rules.FERAL_H * H) { graze(g); return evs }
        g.feral = false
        g.truceUntil = 0
        g.truceUsed = false
        if (wasFeral) {
            g.note(at, "calm", "You shipped. The horde calmed down.")
            evs.add(Event(Ev.CALMED, g.lead))
        }
        graze(g)
        return evs
    }

    internal fun gapIsRest(g: Game, from: Long, to: Long, tz: Long): Boolean {
        if (to <= from) return true
        if (to - from > 40) return false
        val hFrom = Rules.localDay(g.hibernateFrom, tz)
        val hTo = Rules.localDay(g.hibernateUntil, tz)
        for (d in from + 1 until to) {
            val hib = g.hibernateUntil > 0 && d in hFrom..hTo
            if (!hib && !restDay(g, d)) return false
        }
        return true
    }

    // ------------------------------------------------------------ monsters

    private fun spawn(g: Game, species: String, at: Long): Monster {
        val m = Monster(g.nextId++, species, Dex.get(species).name, bornAt = at)
        g.monsters.add(m)
        g.seen.add(species)
        g.hatched++
        if (g.party.size < g.slots) g.party.add(m.id)
        g.note(at, "hatch", "${m.name} hatched.")
        return m
    }

    /** Up to three commons to choose from: the classic three at first, later whichever are not owned. */
    fun starters(g: Game): List<String> {
        val free = Dex.of(Rarity.COMMON).map { it.id }.filter { g.owned(it) == null }
        val classic = Dex.starters.filter { it in free }
        val pool = (classic + free.filter { it !in classic } + Dex.starters).distinct()
        return pool.take(3)
    }

    fun pickStarter(g: Game, species: String): Boolean {
        if (!g.needsStarter || species !in starters(g)) return false
        g.starter = species
        return true
    }

    fun toParty(g: Game, id: Int): Boolean {
        val m = g.monster(id) ?: return false
        if (m.stone || id in g.party || g.party.size >= g.slots) return false
        if (mood(g) == Mood.RESTLESS || mood(g) == Mood.FERAL) return false   // nobody changes sides while the horde is uneasy
        g.party.add(id)
        return true
    }

    fun toDen(g: Game, id: Int): Boolean {
        if (id !in g.party || g.party.size <= 1 || mood(g) != Mood.CALM) return false
        g.party.remove(Integer.valueOf(id))
        return true
    }

    fun makeLead(g: Game, id: Int): Boolean {
        if (id !in g.party) return false
        g.party.remove(Integer.valueOf(id))
        g.party.add(0, id)
        return true
    }

    fun rename(g: Game, id: Int, name: String): Boolean {
        val clean = name.trim().take(14)
        if (clean.isEmpty()) return false
        g.monster(id)?.name = clean
        return true
    }

    // ------------------------------------------------------------ spending

    /** 2 = favourite flavour, 1 = fed, -1 = not in the party, -2 = no treats left today, -3 = not enough chow. */
    fun treat(g: Game, id: Int, now: Long, evs: ArrayList<Event> = ArrayList()): Int {
        val m = g.monster(id) ?: return -1
        if (id !in g.party || m.stone) return -1
        if (m.treats >= Rules.TREATS_PER_DAY) return -2
        if (g.pantry < Rules.TREAT_COST) return -3
        val fav = m.sp.fav.ordinal
        var gotFav = false
        repeat(Rules.TREAT_COST) {
            var f = fav
            if (g.chow[f] <= 0) { f = 0; for (i in 1..3) if (g.chow[i] > g.chow[f]) f = i } else gotFav = true
            g.chow[f]--
        }
        m.belly = (m.belly + 10).coerceAtMost(100.0)
        m.treats++
        g.treatsGiven++
        if (gotFav) m.ateFav = true
        grow(g, m, if (gotFav) 2.0 else 1.0, now, evs)
        return if (gotFav) 2 else 1
    }

    fun feast(g: Game, now: Long): List<Event>? {
        if (g.gems < Rules.FEAST_COST || g.monsters.isEmpty()) return null
        val evs = ArrayList<Event>()
        g.gems -= Rules.FEAST_COST
        g.feasts++
        for (m in g.statues()) wakeStatue(g, m)
        for (m in g.partyMonsters()) {
            m.belly = 100.0
            m.emptyMs = 0
            grow(g, m, 10.0, now, evs)
        }
        g.note(now, "feast", "You threw a feast. Every belly is full.")
        return evs
    }

    fun stockUp(g: Game, now: Long, tz: Long, evs: ArrayList<Event> = ArrayList()): Boolean {
        if (g.gems < 1) return false
        g.gems--
        addChow(g, Rules.STOCK_CHOW, Rules.flavorAt(Rules.localHour(now, tz)), now, evs)
        g.note(now, "stock", "Stocked the pantry. +${Rules.STOCK_CHOW} chow.")
        return true
    }

    fun revive(g: Game, id: Int, now: Long): Boolean {
        val m = g.monster(id) ?: return false
        if (!m.stone || g.gems < 1) return false
        g.gems--
        wakeStatue(g, m)
        g.note(now, "revive", "${m.name} woke from stone.")
        return true
    }

    private fun wakeStatue(g: Game, m: Monster) {
        m.stone = false
        m.belly = 100.0
        m.emptyMs = 0
        if (g.party.size < g.slots) g.party.add(m.id)
    }

    fun truce(g: Game, now: Long): Boolean {
        if (mood(g) != Mood.FERAL || g.truceUsed || g.gems < 1) return false
        g.gems--
        g.truceUsed = true
        g.truceUntil = now + Rules.TRUCE_H * H
        g.note(now, "truce", "Bought a ${Rules.TRUCE_H}-hour truce with a gem.")
        return true
    }

    fun surrender(g: Game, now: Long): List<Event> {
        val evs = ArrayList<Event>()
        if (mood(g) == Mood.FERAL) fall(g, now, evs)
        return evs
    }

    fun hibernate(g: Game, now: Long, days: Int): Boolean {
        if (g.feral || days !in 1..Rules.MAX_HIBERNATE_DAYS) return false
        if (g.hibernateUntil > 0 && now - g.hibernateUntil < Rules.HIBERNATE_COOLDOWN_DAYS * D) return false
        g.hibernateFrom = now
        g.hibernateUntil = now + days * D
        g.note(now, "rest", "The horde is hibernating for $days days.")
        return true
    }

    fun wake(g: Game, now: Long) {
        if (hibernating(g, now)) {
            g.hibernateUntil = now
            g.note(now, "rest", "The horde woke up early.")
        }
    }

    // ------------------------------------------------------------ eggs

    private fun rnd(g: Game, n: Int): Int {
        g.seed = g.seed * 6364136223846793005L + 1442695040888963407L
        return ((g.seed ushr 33) % n).toInt()
    }

    private fun rollRarity(g: Game): Rarity {
        g.sinceRare++; g.sinceEpic++; g.sinceLegendary++
        val x = rnd(g, 100)
        var r = when { x < 2 -> Rarity.LEGENDARY; x < 12 -> Rarity.EPIC; x < 40 -> Rarity.RARE; else -> Rarity.COMMON }
        if (g.sinceLegendary >= 60) r = Rarity.LEGENDARY
        else if (g.sinceEpic >= 20 && r.ordinal < Rarity.EPIC.ordinal) r = Rarity.EPIC
        else if (g.sinceRare >= 5 && r == Rarity.COMMON) r = Rarity.RARE
        if (r.ordinal >= Rarity.RARE.ordinal) g.sinceRare = 0
        if (r.ordinal >= Rarity.EPIC.ordinal) g.sinceEpic = 0
        if (r == Rarity.LEGENDARY) g.sinceLegendary = 0
        return r
    }

    fun buyEgg(g: Game, now: Long): Egg? {
        if (g.gems < 1) return null
        g.gems--
        val pool = Dex.of(rollRarity(g))
        var pick = pool[rnd(g, pool.size)]
        if (g.owned(pick.id) != null && pool.size > 1 && rnd(g, 2) == 0) pick = pool[rnd(g, pool.size)]
        val egg = Egg(pick.id)
        g.nest.add(egg)
        g.note(now, "egg", "Bought a ${pick.rarity.label.lowercase()} egg.")
        return egg
    }

    /** Three taps crack an egg. Returns what came out on the last tap, otherwise null. */
    fun tapEgg(g: Game, index: Int, now: Long): Hatch? {
        val egg = g.nest.getOrNull(index) ?: return null
        egg.taps++
        if (egg.taps < 3) return null
        g.nest.removeAt(index)
        val have = g.owned(egg.species) ?: return Hatch(spawn(g, egg.species, now), true, false, false, 0)
        var revived = false
        if (have.stone) { wakeStatue(g, have); revived = true }
        if (have.stars < Rules.MAX_STARS) {
            have.stars++
            g.note(now, "star", "Another ${have.sp.name}. ${have.name} earned a star" + (if (revived) " and woke from stone." else "."))
            return Hatch(have, false, true, revived, 0)
        }
        addChow(g, 30, have.sp.fav, now, ArrayList())
        g.note(now, "star", "Another ${have.sp.name}. ${have.name} is maxed out, so it came with 30 chow.")
        return Hatch(have, false, false, revived, 30)
    }
}
