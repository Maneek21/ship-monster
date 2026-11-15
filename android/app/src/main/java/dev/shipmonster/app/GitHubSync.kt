package dev.shipmonster.app

import android.content.Context
import dev.shipmonster.app.core.Ev
import android.util.Log
import java.io.IOException
import java.time.Instant

/** [situation] is the dialogue situation that best sums up what the sync brought. */
class SyncOutcome(val message: String, val fed: Int, val ok: Boolean, val situation: String = "fedpush")

/**
 * Pulls new pushes and merges from GitHub and feeds the pet.
 *
 * One request lists the user's most recently pushed repos. Only repos whose push time changed
 * get a second request for their activity feed. With no token this sees public repos the user
 * owns. With a token it also sees private repos and ones they collaborate on.
 */
object GitHubSync {
    private const val TAG = "ShipMonster"
    private val lock = Any()
    private const val DAY = 24 * 60 * 60 * 1000L

    fun run(c: Context): SyncOutcome = synchronized(lock) {
        val user = Prefs.username(c)
        if (user.isEmpty()) return@synchronized SyncOutcome("Add your GitHub username in Setup.", 0, false)
        try {
            sync(c, user)
        } catch (e: IOException) {
            fail(c, "No connection to GitHub. Will retry.")
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            fail(c, "Sync hit an unexpected problem. Will retry.")
        }
    }

    private fun fail(c: Context, message: String): SyncOutcome {
        Log.i(TAG, "sync stopped: $message")
        Prefs.saveSyncFailure(c, message)
        return SyncOutcome(message, 0, false)
    }

    private fun sync(c: Context, user: String): SyncOutcome {
        val token = Prefs.token(c)
        val realNow = System.currentTimeMillis()
        val first = !Prefs.firstSyncDone(c)
        val lastSeen = Prefs.lastSeen(c)

        val listPath = if (token != null) {
            "/user/repos?sort=pushed&direction=desc&per_page=15&affiliation=owner,collaborator,organization_member"
        } else {
            "/users/$user/repos?sort=pushed&direction=desc&per_page=15"
        }
        val list = GitHubApi.get(listPath, token, Prefs.reposEtag(c))
        var etag = Prefs.reposEtag(c)
        val repos: List<RepoInfo> = when (list.code) {
            200 -> {
                etag = list.etag
                SyncLogic.parseRepos(list.body)
            }
            304 -> emptyList()
            401 -> return fail(c, "GitHub rejected the token. Check it in Setup.")
            403, 429 -> return fail(c, "GitHub rate limit reached. Add a token in Setup, or wait an hour.")
            404 -> return fail(c, "GitHub has no user named $user.")
            else -> return fail(c, "GitHub answered with error ${list.code}.")
        }

        val pushedMap = Prefs.repoPushed(c)
        val horizon = if (first) realNow - 30 * DAY else lastSeen
        val candidates = repos
            .filter { it.pushedAt > horizon && pushedMap[it.fullName] != it.pushedAt }
            .take(if (first) 6 else 4)

        val events = mutableListOf<ShipEvent>()
        for (r in candidates) {
            val period = if (first) "month" else "week"
            val act = GitHubApi.get("/repos/${r.fullName}/activity?per_page=100&time_period=$period&actor=$user", token)
            if (act.code == 200) {
                events.addAll(SyncLogic.parseActivity(act.body, r.fullName, user))
            } else {
                val since = Instant.ofEpochMilli(maxOf(horizon, realNow - 30 * DAY)).toString()
                val commits = GitHubApi.get("/repos/${r.fullName}/commits?per_page=50&since=$since", token)
                if (commits.code == 200) events.addAll(SyncLogic.parseCommits(commits.body, r.fullName))
            }
            pushedMap[r.fullName] = r.pushedAt
        }

        val fresh = if (first) SyncLogic.firstSyncWindow(events, realNow) else SyncLogic.newEvents(events, lastSeen)
        // The game clock is real time plus whatever the test lab skipped, so real events are moved by the same amount.
        val offset = GameStore.clockOffset(c)
        val toApply = fresh.map { it.copy(time = it.time + offset) }
        var wasRed = false
        val (summary, message) = GameStore.edit(c) { g, _ ->
            wasRed = g.ciRed
            val sum = SyncLogic.apply(g, toApply, GameStore.tz(realNow), backdate = first)
            sum to SyncLogic.message(sum, g, first)
        }
        if (summary.events.isNotEmpty() && !first) Notifier.onEvents(c, GameStore.load(c), summary.events.filter { it.kind != Ev.HATCHED })
        Log.i(TAG, "sync first=$first repos=${repos.size} checked=${candidates.size} events=${events.size} applied=${summary.fed}")

        // CI health of the repo shipped to most recently. A red build shows up in what the monsters say.
        val ciRepo = toApply.lastOrNull()?.repo ?: Prefs.lastRepo(c)
        if (ciRepo != null && (toApply.isNotEmpty() || wasRed)) {
            val runs = GitHubApi.get("/repos/$ciRepo/actions/runs?per_page=1&status=completed", token)
            if (runs.code == 200) SyncLogic.parseCiFailing(runs.body)?.let { red -> GameStore.edit(c) { g, _ -> g.ciRed = red } }
        }

        val newest = events.maxOfOrNull { it.time } ?: 0L
        val marker = if (first) maxOf(newest, realNow - 5 * 60 * 1000L) else maxOf(lastSeen, newest)
        Prefs.saveSync(c, marker, message, etag, pushedMap, ciRepo)
        val situation = when {
            summary.hatched != null -> "hatched"
            summary.calmed -> "calmed"
            summary.evolved != null -> "evolved"
            summary.merges > 0 -> "merged"
            else -> "fedpush"
        }
        return SyncOutcome(message, summary.fed, true, situation)
    }
}
