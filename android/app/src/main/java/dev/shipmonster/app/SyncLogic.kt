package dev.shipmonster.app

import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Ev
import dev.shipmonster.app.core.Event
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Monster
import dev.shipmonster.app.core.Rules

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A repository and when it last received a push. */
data class RepoInfo(val fullName: String, val pushedAt: Long)

/** One thing that feeds the pet: a push, or a merged pull request. */
data class ShipEvent(val time: Long, val merge: Boolean, val repo: String)

class ApplySummary(
    val fed: Int,
    val merges: Int,
    val hatched: Monster?,
    val calmed: Boolean,
    val evolved: Monster?,
    val gems: Int,
    val events: List<Event>,
)

/**
 * The parts of GitHub sync that need no network or Android APIs, so they can be unit tested.
 *
 * Data source: the repository activity feed (GET /repos/{owner}/{repo}/activity). Unlike the
 * user events feed it is not delayed, it names who pushed, it covers every branch, and it
 * marks pull request merges.
 */
object SyncLogic {
    private val SHIP_TYPES = setOf("push", "force_push", "branch_creation")
    private val MERGE_TYPES = setOf("pr_merge", "merge_queue_merge")

    private fun str(o: JSONObject, key: String): String? = if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    fun parseTime(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    fun validUsername(name: String): Boolean = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$").matches(name)

    /** From GET /users/{user}/repos or GET /user/repos. Archived repos are skipped. */
    fun parseRepos(json: String): List<RepoInfo> {
        val out = mutableListOf<RepoInfo>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("archived", false)) continue
            val name = str(o, "full_name") ?: continue
            val pushed = parseTime(str(o, "pushed_at")) ?: continue
            out.add(RepoInfo(name, pushed))
        }
        return out
    }

    /** From GET /repos/{repo}/activity. Keeps only what the given user did, and only activity that counts as shipping. */
    fun parseActivity(json: String, repo: String, username: String): List<ShipEvent> {
        val out = mutableListOf<ShipEvent>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val actor = o.optJSONObject("actor")?.let { str(it, "login") } ?: continue
            if (!actor.equals(username, ignoreCase = true)) continue
            val time = parseTime(str(o, "timestamp")) ?: continue
            val type = str(o, "activity_type") ?: continue
            when (type) {
                in MERGE_TYPES -> out.add(ShipEvent(time, true, repo))
                in SHIP_TYPES -> out.add(ShipEvent(time, false, repo))
            }
        }
        return out
    }

    /**
     * Fallback, from GET /repos/{repo}/commits, for when the activity feed is not readable.
     * Only sees the default branch and cannot tell who pushed, so every commit counts.
     */
    fun parseCommits(json: String, repo: String): List<ShipEvent> {
        val out = mutableListOf<ShipEvent>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val commit = o.optJSONObject("commit") ?: continue
            val who = commit.optJSONObject("committer") ?: commit.optJSONObject("author") ?: continue
            val time = parseTime(str(who, "date")) ?: continue
            val parents = o.optJSONArray("parents")?.length() ?: 0
            out.add(ShipEvent(time, parents >= 2, repo))
        }
        // This feed lists commits, not pushes. Commits within ten minutes of each other count as one push.
        val kept = mutableListOf<ShipEvent>()
        for (e in out.sortedBy { it.time }) {
            val last = kept.lastOrNull()
            if (last == null || e.merge || e.time - last.time > 10 * 60_000L) kept.add(e)
        }
        return kept
    }

    /** From GET /repos/{repo}/actions/runs?per_page=1&status=completed. Null when the repo has no finished runs. */
    fun parseCiFailing(json: String): Boolean? {
        val runs = JSONObject(json).optJSONArray("workflow_runs") ?: return null
        if (runs.length() == 0) return null
        val conclusion = str(runs.getJSONObject(0), "conclusion") ?: return null
        return conclusion == "failure" || conclusion == "timed_out" || conclusion == "startup_failure"
    }

    /** Events newer than the marker, oldest first. */
    fun newEvents(events: List<ShipEvent>, lastSeen: Long): List<ShipEvent> =
        events.filter { it.time > lastSeen }.sortedBy { it.time }

    /**
     * First sync only. Picks the most recent unbroken run of shipping: the newest event must be
     * less than 48 hours old, and no gap inside the run may reach 48 hours. That way a new pet
     * starts with the user's real streak and never arrives already a monster.
     */
    fun firstSyncWindow(events: List<ShipEvent>, now: Long, awake: (Long, Long) -> Long = { a, b -> b - a }): List<ShipEvent> {
        val sorted = events.filter { it.time <= now }.sortedBy { it.time }
        if (sorted.isEmpty()) return emptyList()
        val limit = Rules.FERAL_H * Rules.H
        if (awake(sorted.last().time, now) >= limit) return emptyList()
        var start = sorted.size - 1
        while (start > 0 && awake(sorted[start - 1].time, sorted[start].time) < limit) start--
        return sorted.subList(start, sorted.size)
    }

    /**
     * Feeds the horde with the events, oldest first. backdate lets a first sync replay history from
     * before the game existed. Events at or before the last ship already counted are ignored.
     */
    fun apply(g: Game, events: List<ShipEvent>, tzOffsetMs: Long, backdate: Boolean): ApplySummary {
        var fed = 0
        var merges = 0
        var hatched: Monster? = null
        var calmed = false
        var evolved: Monster? = null
        val gemsBefore = g.gems
        val all = ArrayList<Event>()
        val sorted = events.sortedBy { it.time }
        if (backdate && sorted.isNotEmpty()) Engine.rewind(g, sorted.first().time)
        for (e in sorted) {
            if (g.lastShipAt != 0L && e.time <= g.lastShipAt) continue
            if (g.needsStarter) break
            val evs = Engine.ship(g, e.time, e.merge, tzOffsetMs, e.repo)
            all.addAll(evs)
            fed++
            if (e.merge) merges++
            for (ev in evs) when (ev.kind) {
                Ev.HATCHED -> hatched = ev.monster
                Ev.CALMED -> calmed = true
                Ev.EVOLVED -> evolved = ev.monster
                else -> {}
            }
        }
        // A first sync replays up to a month of work. Keep the growth and the streak, but start
        // everyone with a small purse: at least one gem for a first egg, at most three.
        if (backdate) {
            g.gems = g.gems.coerceIn(1, 3)
            g.lifetimeChow = g.lifetimeChow.coerceAtMost(Rules.LEVEL_CHOW[2] - 1)
            var extra = g.pantry - 40
            while (extra > 0) {
                var f = 0
                for (i in 1..3) if (g.chow[i] > g.chow[f]) f = i
                g.chow[f]--
                extra--
            }
        }
        return ApplySummary(fed, merges, hatched, calmed, evolved, g.gems - gemsBefore, all)
    }

    fun message(summary: ApplySummary, g: Game, first: Boolean): String {
        val hatched = summary.hatched
        val evolved = summary.evolved
        val gems = if (summary.gems > 0) " +${summary.gems} gem" + (if (summary.gems > 1) "s." else ".") else ""
        return when {
            g.needsStarter -> "Pick a starter egg first."
            hatched != null && first -> "Found your recent work. ${hatched.name} hatched with a ${g.streak}-day streak."
            hatched != null -> "${hatched.name} hatched."
            summary.calmed -> "You shipped. The horde is calm and your apps are open."
            evolved != null -> "${evolved.name} grew into a ${evolved.stageName}.$gems"
            summary.fed > 0 -> (if (summary.fed == 1) "1 new ship. The horde ate." else "${summary.fed} new ships. The horde ate.") + gems
            first -> "No ships in the last 48 hours. The egg hatches on your next push."
            else -> "Up to date. Nothing new."
        }
    }
}
