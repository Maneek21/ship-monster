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
                Log.w("ShipMonster", "saved game unreadable, starting over", e)
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

    /** Loads, advances to now, runs [block], saves. Returns what the block returns. */
    fun <T> edit(c: Context, block: (Game, Long) -> T): T {
        val app = c.applicationContext
        val events: List<Event>
        val out: T
        val g: Game
        synchronized(lock) {
            g = read(app)
            val now = now(app)
            events = Engine.advance(g, now, tz(now))
            out = block(g, now)
            prefs(app).edit().putString(KEY_STATE, g.toJson()).apply()
        }
        if (events.isNotEmpty()) Notifier.onEvents(app, g, events)
        return out
    }

    fun reset(c: Context) {
        synchronized(lock) { prefs(c).edit().clear().apply() }
    }
}
