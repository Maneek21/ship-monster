package dev.shipmonster.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Saves the pet in SharedPreferences. Also holds the test-only clock offset used by the skip-time buttons. */
object PetStore {
    private const val PREFS = "pet"
    private const val KEY_STATE = "state"
    private const val KEY_OFFSET = "debug_offset_ms"

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Real time plus any skipped test time. */
    fun now(c: Context): Long = System.currentTimeMillis() + prefs(c).getLong(KEY_OFFSET, 0L)

    fun tzOffset(now: Long): Long = TimeZone.getDefault().getOffset(now).toLong()

    /** How far the test lab has pushed the pet clock ahead of real time. */
    fun clockOffset(c: Context): Long = prefs(c).getLong(KEY_OFFSET, 0L)

    fun resetClock(c: Context) {
        prefs(c).edit().remove(KEY_OFFSET).apply()
    }

    fun skip(c: Context, hours: Int) {
        val p = prefs(c)
        p.edit().putLong(KEY_OFFSET, p.getLong(KEY_OFFSET, 0L) + hours * PetEngine.H).apply()
    }

    fun load(c: Context): PetState {
        val now = now(c)
        val raw = prefs(c).getString(KEY_STATE, null)
        var s: PetState? = null
        if (raw != null) {
            s = try {
                fromJson(JSONObject(raw))
            } catch (e: Exception) {
                null
            }
        }
        val state = s ?: PetEngine.newState(now)
        if (s == null || PetEngine.tick(state, now)) save(c, state)
        return state
    }

    fun save(c: Context, s: PetState) {
        prefs(c).edit().putString(KEY_STATE, toJson(s).toString()).apply()
    }

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
    }

    private fun optLong(o: JSONObject, key: String): Long? = if (o.has(key) && !o.isNull(key)) o.getLong(key) else null

    private fun petToJson(p: Pet): JSONObject {
        val o = JSONObject()
        o.put("name", p.name)
        o.put("bornAt", p.bornAt)
        p.hatchedAt?.let { o.put("hatchedAt", it) }
        p.lastFedAt?.let { o.put("lastFedAt", it) }
        o.put("streak", p.streak)
        o.put("bestStreak", p.bestStreak)
        p.lastShipDay?.let { o.put("lastShipDay", it) }
        o.put("form", p.form.name)
        o.put("commits", p.commits)
        o.put("merges", p.merges)
        o.put("ciFailing", p.ciFailing)
        p.deadAt?.let { o.put("deadAt", it) }
        p.deadCause?.let { o.put("deadCause", it.name) }
        return o
    }

    private fun petFromJson(o: JSONObject): Pet = Pet(
        name = o.getString("name"),
        bornAt = o.getLong("bornAt"),
        hatchedAt = optLong(o, "hatchedAt"),
        lastFedAt = optLong(o, "lastFedAt"),
        streak = o.optInt("streak", 0),
        bestStreak = o.optInt("bestStreak", 0),
        lastShipDay = optLong(o, "lastShipDay"),
        form = Form.valueOf(o.optString("form", Form.EGG.name)),
        commits = o.optInt("commits", 0),
        merges = o.optInt("merges", 0),
        ciFailing = o.optBoolean("ciFailing", false),
        deadAt = optLong(o, "deadAt"),
        deadCause = if (o.has("deadCause")) Cause.valueOf(o.getString("deadCause")) else null
    )

    private fun toJson(s: PetState): JSONObject {
        val graves = JSONArray()
        for (g in s.graveyard) {
            val o = JSONObject()
            o.put("name", g.name)
            o.put("form", g.form.name)
            o.put("cause", g.cause.name)
            o.put("diedAt", g.diedAt)
            o.put("days", g.days)
            o.put("bestStreak", g.bestStreak)
            o.put("ships", g.ships)
            graves.put(o)
        }
        val root = JSONObject()
        root.put("pet", petToJson(s.pet))
        root.put("petCount", s.petCount)
        root.put("graveyard", graves)
        return root
    }

    private fun fromJson(root: JSONObject): PetState {
        val graves = mutableListOf<Grave>()
        val arr = root.optJSONArray("graveyard") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            graves.add(
                Grave(
                    name = o.getString("name"),
                    form = Form.valueOf(o.getString("form")),
                    cause = Cause.valueOf(o.getString("cause")),
                    diedAt = o.getLong("diedAt"),
                    days = o.getInt("days"),
                    bestStreak = o.optInt("bestStreak", 0),
                    ships = o.optInt("ships", 0)
                )
            )
        }
        return PetState(petFromJson(root.getJSONObject("pet")), root.optInt("petCount", 1), graves)
    }
}
