package dev.shipmonster.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A repository and when it last received a push. */
data class RepoInfo(val fullName: String, val pushedAt: Long)

/** One thing that feeds the pet: a push, or a merged pull request. */
data class ShipEvent(val time: Long, val merge: Boolean, val repo: String)

data class ApplySummary(
    val fed: Int = 0,
    val merges: Int = 0,
    val hatched: Boolean = false,
    val turnedBack: Boolean = false,
    val evolved: Form? = null
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
        return out
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
    fun firstSyncWindow(events: List<ShipEvent>, now: Long): List<ShipEvent> {
        val sorted = events.filter { it.time <= now }.sortedBy { it.time }
        if (sorted.isEmpty()) return emptyList()
        val limit = PetEngine.MONSTER_AT * PetEngine.H
        if (now - sorted.last().time >= limit) return emptyList()
        var start = sorted.size - 1
        while (start > 0 && sorted[start].time - sorted[start - 1].time < limit) start--
        return sorted.subList(start, sorted.size)
    }

    /**
     * Feeds the pet with the events, oldest first. backdate lets an unhatched egg be born in the
     * past so first-sync history can count. Events from before the pet existed, or older than
     * its last meal, are ignored, and a dead pet stays dead.
     */
    fun apply(s: PetState, events: List<ShipEvent>, tzOffsetMs: Long, backdate: Boolean): ApplySummary {
        var fed = 0
        var merges = 0
        var hatched = false
        var turnedBack = false
        var evolved: Form? = null
        for (e in events.sortedBy { it.time }) {
            var p = s.pet
            if (p.deadAt != null) break
            if (p.hatchedAt == null && e.time < p.bornAt) {
                if (!backdate) continue
                p = p.copy(bornAt = e.time)
                s.pet = p
            }
            val lastFed = p.lastFedAt
            if (lastFed != null && e.time <= lastFed) continue
            val r = PetEngine.ship(s, e.time, e.merge, tzOffsetMs) ?: break
            fed++
            if (e.merge) merges++
            if (r.hatched) hatched = true
            if (r.was == Status.MONSTER) turnedBack = true
            if (r.evolved != null) evolved = r.evolved
        }
        return ApplySummary(fed, merges, hatched, turnedBack, evolved)
    }

    fun message(summary: ApplySummary, s: PetState, first: Boolean): String {
        val name = s.pet.name
        return when {
            s.pet.deadAt != null -> "$name died before these ships arrived. Hatch a new egg."
            summary.hatched && first -> "Found your recent work. $name hatched with a ${s.pet.streak}-day streak."
            summary.hatched -> "$name hatched."
            summary.turnedBack -> "$name turned back. Apps unlocked."
            summary.evolved != null -> "$name grew into a ${PetText.formLabel(summary.evolved)}."
            summary.fed > 0 -> if (summary.fed == 1) "1 new ship. $name ate." else "${summary.fed} new ships. $name ate."
            first -> "No ships in the last 48 hours. Ship something to hatch the egg."
            else -> "Up to date. Nothing new."
        }
    }
}
