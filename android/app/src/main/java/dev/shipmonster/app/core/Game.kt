package dev.shipmonster.app.core

import org.json.JSONArray
import org.json.JSONObject

class Monster(
    val id: Int,
    val species: String,
    var name: String,
    var gp: Double = 0.0,
    var stars: Int = 0,
    var belly: Double = 100.0,
    var stone: Boolean = false,
    var bornAt: Long = 0,
    var treats: Int = 0,
    var ateFav: Boolean = false,
    var emptyMs: Long = 0,
    var peak: Int = 1,
) {
    val sp: Species get() = Dex.get(species)
    val stage: Int get() = Rules.stageFor(gp)
    val stageName: String get() = Rules.STAGE_NAMES[stage]
    /** "Pip the Beast", or "Pip the Evergreen" for a Titan. */
    val title: String get() = if (stage >= 8) "$name ${sp.epithet}" else "$name the $stageName"
}

class Egg(val species: String, var taps: Int = 0)

class LogEntry(val at: Long, val kind: String, val text: String)

class Game {
    var version = 1
    var createdAt = 0L
    var lastTick = 0L
    var seed = 0x5DEECE66DL

    val monsters = ArrayList<Monster>()
    val party = ArrayList<Int>()          // monster ids, lead first
    var nextId = 1
    var starter: String? = null           // the egg waiting for a first ship
    val nest = ArrayList<Egg>()           // bought eggs waiting to be cracked

    val chow = IntArray(4)                // by Flavor.ordinal
    var gems = 0
    var lifetimeChow = 0

    var lastShipAt = 0L
    var lastShipDay = Long.MIN_VALUE
    var idleMs = 0L                       // awake time since the last ship
    var feral = false
    var truceUntil = 0L
    var truceUsed = false
    var streak = 0
    var bestStreak = 0

    var day = Long.MIN_VALUE              // local day the daily counters belong to
    var pushesToday = 0
    var gemsToday = 0
    var week = Long.MIN_VALUE
    var weekShipDays = 0
    var weekPaid = false

    val restDays = HashSet<Int>().apply { add(0); add(6) }
    var hibernateFrom = 0L
    var hibernateUntil = 0L

    var sinceRare = 0
    var sinceEpic = 0
    var sinceLegendary = 0

    var ciRed = false
    var pushes = 0
    var merges = 0
    var hatched = 0
    var falls = 0
    var feasts = 0
    var treatsGiven = 0
    var shipDays = 0

    val log = ArrayList<LogEntry>()
    val hall = ArrayList<LogEntry>()
    val seen = HashSet<String>()          // species ever owned, for the dex

    val level: Int get() = Rules.levelFor(lifetimeChow)
    val slots: Int get() = Rules.slots(level)
    val pantry: Int get() = chow.sum()
    val pantryCap: Int get() = Rules.pantryCap(level)

    fun monster(id: Int): Monster? = monsters.firstOrNull { it.id == id }
    fun partyMonsters(): List<Monster> = party.mapNotNull { monster(it) }
    val lead: Monster? get() = party.firstOrNull()?.let { monster(it) }
    fun den(): List<Monster> = monsters.filter { !it.stone && it.id !in party }
    fun statues(): List<Monster> = monsters.filter { it.stone }
    fun owned(species: String): Monster? = monsters.firstOrNull { it.species == species }
    /** True when there is nothing alive and no egg: the player must choose a starter. */
    val needsStarter: Boolean get() = starter == null && monsters.none { !it.stone }

    fun note(at: Long, kind: String, text: String) {
        log.add(0, LogEntry(at, kind, text))
        while (log.size > 80) log.removeAt(log.size - 1)
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version).put("createdAt", createdAt).put("lastTick", lastTick).put("seed", seed)
        val ms = JSONArray()
        for (m in monsters) ms.put(JSONObject()
            .put("id", m.id).put("species", m.species).put("name", m.name).put("gp", m.gp)
            .put("stars", m.stars).put("belly", m.belly).put("stone", m.stone).put("bornAt", m.bornAt)
            .put("treats", m.treats).put("ateFav", m.ateFav).put("emptyMs", m.emptyMs).put("peak", m.peak))
        o.put("monsters", ms)
        o.put("party", JSONArray(party)).put("nextId", nextId)
        if (starter != null) o.put("starter", starter)
        val ns = JSONArray()
        for (e in nest) ns.put(JSONObject().put("species", e.species).put("taps", e.taps))
        o.put("nest", ns)
        o.put("chow", JSONArray(chow.toList())).put("gems", gems).put("lifetimeChow", lifetimeChow)
        o.put("lastShipAt", lastShipAt).put("lastShipDay", lastShipDay).put("idleMs", idleMs)
            .put("feral", feral).put("truceUntil", truceUntil).put("truceUsed", truceUsed)
            .put("streak", streak).put("bestStreak", bestStreak)
        o.put("day", day).put("pushesToday", pushesToday).put("gemsToday", gemsToday)
            .put("week", week).put("weekShipDays", weekShipDays).put("weekPaid", weekPaid)
        o.put("restDays", JSONArray(restDays.sorted())).put("hibernateFrom", hibernateFrom)
            .put("hibernateUntil", hibernateUntil)
        o.put("sinceRare", sinceRare).put("sinceEpic", sinceEpic).put("sinceLegendary", sinceLegendary)
        o.put("ciRed", ciRed).put("pushes", pushes).put("merges", merges).put("hatched", hatched)
            .put("falls", falls).put("feasts", feasts).put("treatsGiven", treatsGiven).put("shipDays", shipDays)
        o.put("log", entries(log)).put("hall", entries(hall)).put("seen", JSONArray(seen.sorted()))
        return o.toString()
    }

    private fun entries(l: List<LogEntry>): JSONArray {
        val a = JSONArray()
        for (e in l) a.put(JSONObject().put("at", e.at).put("kind", e.kind).put("text", e.text))
        return a
    }

    companion object {
        fun fresh(now: Long): Game = Game().apply {
            createdAt = now
            lastTick = now
            seed = now xor 0x5DEECE66DL
        }

        fun fromJson(text: String): Game {
            val o = JSONObject(text)
            val g = Game()
            g.version = o.optInt("version", 1)
            g.createdAt = o.optLong("createdAt")
            g.lastTick = o.optLong("lastTick")
            g.seed = o.optLong("seed", g.seed)
            val ms = o.optJSONArray("monsters") ?: JSONArray()
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                g.monsters.add(Monster(m.getInt("id"), m.getString("species"), m.getString("name"),
                    m.optDouble("gp", 0.0), m.optInt("stars"), m.optDouble("belly", 100.0), m.optBoolean("stone"),
                    m.optLong("bornAt"), m.optInt("treats"), m.optBoolean("ateFav"), m.optLong("emptyMs"),
                    m.optInt("peak", 1)))
            }
            val p = o.optJSONArray("party") ?: JSONArray()
            for (i in 0 until p.length()) g.party.add(p.getInt(i))
            g.nextId = o.optInt("nextId", 1)
            g.starter = if (o.has("starter")) o.getString("starter") else null
            val ns = o.optJSONArray("nest") ?: JSONArray()
            for (i in 0 until ns.length()) ns.getJSONObject(i).let { g.nest.add(Egg(it.getString("species"), it.optInt("taps"))) }
            val c = o.optJSONArray("chow")
            if (c != null) for (i in 0 until minOf(4, c.length())) g.chow[i] = c.getInt(i)
            g.gems = o.optInt("gems"); g.lifetimeChow = o.optInt("lifetimeChow")
            g.lastShipAt = o.optLong("lastShipAt"); g.lastShipDay = o.optLong("lastShipDay", Long.MIN_VALUE)
            g.idleMs = o.optLong("idleMs"); g.feral = o.optBoolean("feral")
            g.truceUntil = o.optLong("truceUntil"); g.truceUsed = o.optBoolean("truceUsed")
            g.streak = o.optInt("streak"); g.bestStreak = o.optInt("bestStreak")
            g.day = o.optLong("day", Long.MIN_VALUE); g.pushesToday = o.optInt("pushesToday")
            g.gemsToday = o.optInt("gemsToday"); g.week = o.optLong("week", Long.MIN_VALUE)
            g.weekShipDays = o.optInt("weekShipDays"); g.weekPaid = o.optBoolean("weekPaid")
            val r = o.optJSONArray("restDays")
            if (r != null) { g.restDays.clear(); for (i in 0 until r.length()) g.restDays.add(r.getInt(i)) }
            g.hibernateFrom = o.optLong("hibernateFrom"); g.hibernateUntil = o.optLong("hibernateUntil")
            g.sinceRare = o.optInt("sinceRare"); g.sinceEpic = o.optInt("sinceEpic")
            g.sinceLegendary = o.optInt("sinceLegendary")
            g.ciRed = o.optBoolean("ciRed"); g.pushes = o.optInt("pushes"); g.merges = o.optInt("merges")
            g.hatched = o.optInt("hatched"); g.falls = o.optInt("falls"); g.feasts = o.optInt("feasts")
            g.treatsGiven = o.optInt("treatsGiven"); g.shipDays = o.optInt("shipDays")
            fun read(key: String, into: ArrayList<LogEntry>) {
                val a = o.optJSONArray(key) ?: return
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    into.add(LogEntry(it.optLong("at"), it.optString("kind"), it.optString("text")))
                }
            }
            read("log", g.log); read("hall", g.hall)
            val sn = o.optJSONArray("seen")
            if (sn != null) for (i in 0 until sn.length()) g.seen.add(sn.getString(i))
            return g
        }
    }
}
