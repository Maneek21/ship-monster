package dev.shipmonster.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Event
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.LineBank
import dev.shipmonster.app.core.Rules
import org.json.JSONObject
import java.util.TimeZone

/** Loads, advances and saves the game. Every read brings the game up to the current time first. */
object GameStore {
    private const val PREFS = "game"
    private const val KEY_STATE = "state"
    private const val KEY_OFFSET = "debug_offset_ms"
    private const val KEY_BROKEN = "state_before"
    private const val GRACE_MS = 3 * 60 * 60 * 1000L
    private var lastSaved: String? = null
    private var cached: Game? = null
    private var cachedAt = 0L
    private val lock = Any()
    private var bank: LineBank? = null

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Game time: real time plus whatever the test lab skipped. */
    fun now(c: Context): Long = System.currentTimeMillis() + clockOffset(c)
    fun tz(now: Long): Long = TimeZone.getDefault().getOffset(now).toLong()
    fun clockOffset(c: Context): Long = prefs(c).getLong(KEY_OFFSET, 0L)
    fun resetClock(c: Context) = prefs(c).edit().remove(KEY_OFFSET).apply()
    fun skip(c: Context, hours: Int) {
        prefs(c).edit().putLong(KEY_OFFSET, clockOffset(c) + hours * Rules.H).apply()
    }

    fun lines(c: Context): LineBank {
        bank?.let { return it }
        val text = try {
            c.applicationContext.assets.open("lines.tsv").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        return LineBank(text).also { bank = it }
    }

    private fun read(c: Context): Game {
        val raw = prefs(c).getString(KEY_STATE, null)
        if (raw != null) {
            try {
                return Game.fromJson(raw)
            } catch (e: Exception) {
                // keep the unreadable save where it can be recovered instead of overwriting it
                Log.w("ShipMonster", "saved game unreadable, starting over", e)
                prefs(c).edit().putString(KEY_BROKEN, raw).commit()
            }
        }
        return migrate(c) ?: Game.fresh(now(c))
    }

    /** Carries a living v0.2 pet over as a Mossback with its streak. */
    private fun migrate(c: Context): Game? {
        val old = c.getSharedPreferences("pet", Context.MODE_PRIVATE)
        val raw = old.getString("state", null) ?: return null
        return try {
            val pet = JSONObject(raw).getJSONObject("pet")
            old.edit().clear().apply()
            if (pet.isNull("hatchedAt") || !pet.isNull("deadAt") || pet.isNull("lastFedAt")) return null
            val now = now(c)
            val fed = pet.getLong("lastFedAt")
            val g = Game.fresh(minOf(now, fed))
            g.starter = "mossback"
            Engine.ship(g, fed, false, tz(fed))
            g.lead?.let { m ->
                m.name = pet.optString("name", m.name).take(14)
                m.gp = when (pet.optString("form")) { "BLOOM" -> 300.0; "LEAFLING" -> 70.0; else -> 30.0 }
                m.peak = m.stage
                m.bornAt = pet.optLong("hatchedAt", fed)
            }
            g.streak = pet.optInt("streak", 1)
            g.bestStreak = g.streak
            g.note(now, "hatch", "Your old pet moved in and joined the horde.")
            Prefs.setOnboarded(c, true)
            g
        } catch (e: Exception) {
            null
        }
    }

    /** The game, brought up to now. Anything that happened on the way is announced. */
    fun load(c: Context): Game = edit(c) { g, _ -> g }

    /** A copy loaded within the last [maxAgeMs], for callers that poll. Never write to it. */
    fun recent(c: Context, maxAgeMs: Long): Game {
        val g = cached
        if (g != null && System.currentTimeMillis() - cachedAt < maxAgeMs) return g
        return load(c)
    }

    /** True while going feral or the fall is being held back for a sync to confirm nothing was shipped. */
    @Volatile
    var holding = false
        private set

    /**
     * How far the game may be advanced right now. Going feral and the fall cannot be undone, so
     * they wait until a sync has looked at GitHub after the deadline. A phone that stays offline
     * gets [GRACE_MS] of benefit of the doubt, then the rules apply.
     */
    private fun limit(c: Context, g: Game, now: Long): Long {
        holding = false
        if (Prefs.username(c).isEmpty()) return now
        val edge = Engine.pointOfNoReturn(g, tz(now))
        if (edge > now) return now
        val checked = Prefs.lastGoodSync(c) + clockOffset(c)
        if (checked >= edge || now - edge > GRACE_MS) return now
        holding = true
        return edge - 1
    }

    /** Loads, advances to now, runs [block], saves. Returns what the block returns. */
    fun <T> edit(c: Context, block: (Game, Long) -> T): T {
        val app = c.applicationContext
        val events: List<Event>
        val out: T
        val g: Game
        synchronized(lock) {
            g = read(app)
            val now = now(app)
            val upTo = limit(app, g, now)
            events = Engine.advance(g, upTo, tz(now))
            out = block(g, now)
            val json = g.toJson()
            if (json != lastSaved) {
                prefs(app).edit().putString(KEY_STATE, json).apply()
                lastSaved = json
            }
            cached = g
            cachedAt = System.currentTimeMillis()
        }
        if (events.isNotEmpty()) Notifier.onEvents(app, g, events)
        if (holding) SyncJobService.soon(app)
        return out
    }

    /** The save as text, for moving to another phone. */
    fun export(c: Context): String = synchronized(lock) { prefs(c).getString(KEY_STATE, null) ?: "" }

    /** Replaces the game with an exported save. The old one is kept aside. False if the text is not a save. */
    fun import(c: Context, text: String): Boolean = synchronized(lock) {
        val g = try { Game.fromJson(text.trim()) } catch (e: Exception) { return@synchronized false }
        if (g.createdAt <= 0L) return@synchronized false
        val p = prefs(c)
        p.edit().putString(KEY_BROKEN, p.getString(KEY_STATE, null)).putString(KEY_STATE, g.toJson()).commit()
        lastSaved = null
        cached = null
        true
    }

    fun reset(c: Context) {
        synchronized(lock) {
            val p = prefs(c)
            val old = p.getString(KEY_STATE, null)
            p.edit().clear().putString(KEY_BROKEN, old).commit()
            lastSaved = null
            cached = null
        }
    }
}
