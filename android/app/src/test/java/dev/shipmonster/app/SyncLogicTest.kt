package dev.shipmonster.app

import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Rules
import org.junit.Assert.assertNotNull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncLogicTest {
    private val h = Rules.H
    private val now = 1000 * Rules.H
    private fun t(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test fun parsesReposAndSkipsArchivedOrNeverPushed() {
        val json = """
            [
              {"full_name": "me/alpha", "pushed_at": "2026-11-12T15:09:15Z", "archived": false},
              {"full_name": "me/old", "pushed_at": "2020-01-01T00:00:00Z", "archived": true},
              {"full_name": "me/empty", "pushed_at": null}
            ]
        """
        val repos = SyncLogic.parseRepos(json)
        assertEquals(1, repos.size)
        assertEquals("me/alpha", repos[0].fullName)
        assertEquals(t("2026-11-12T15:09:15Z"), repos[0].pushedAt)
    }

    @Test fun activityKeepsOnlyTheUsersShippingActions() {
        // Field names match what the live endpoint returned for this repo on 12 Nov 2026.
        val json = """
            [
              {"activity_type": "pr_merge", "ref": "refs/heads/main", "timestamp": "2026-11-12T16:00:00Z", "actor": {"login": "Maneek21"}},
              {"activity_type": "push", "ref": "refs/heads/dev", "timestamp": "2026-11-12T15:30:00Z", "actor": {"login": "maneek21"}},
              {"activity_type": "branch_creation", "ref": "refs/heads/claude/android-v0", "timestamp": "2026-11-12T15:09:15Z", "actor": {"login": "Maneek21"}},
              {"activity_type": "branch_deletion", "ref": "refs/heads/tmp", "timestamp": "2026-11-12T15:05:00Z", "actor": {"login": "Maneek21"}},
              {"activity_type": "push", "ref": "refs/heads/main", "timestamp": "2026-11-12T15:01:00Z", "actor": {"login": "someone-else"}},
              {"activity_type": "push", "ref": "refs/heads/main", "timestamp": "2026-11-12T15:00:00Z", "actor": null}
            ]
        """
        val events = SyncLogic.parseActivity(json, "Maneek21/ship-monster", "Maneek21")
        assertEquals(3, events.size)
        assertTrue(events[0].merge)
        assertFalse(events[1].merge)
        assertEquals(t("2026-11-12T15:09:15Z"), events[2].time)
        assertEquals("Maneek21/ship-monster", events[2].repo)
    }

    @Test fun commitsFallbackTreatsTwoParentsAsAMerge() {
        val json = """
            [
              {"sha": "a", "commit": {"committer": {"date": "2026-11-12T10:00:00Z"}}, "parents": [{"sha": "x"}, {"sha": "y"}]},
              {"sha": "b", "commit": {"author": {"date": "2026-11-11T10:00:00Z"}}, "parents": [{"sha": "x"}]}
            ]
        """
        val events = SyncLogic.parseCommits(json, "me/alpha")
        assertEquals(2, events.size)
        assertFalse(events[0].merge)     // oldest first
        assertTrue(events[1].merge)
        // commits minutes apart are one push
        val burst = """
            [
              {"sha": "a", "commit": {"committer": {"date": "2026-11-12T10:00:00Z"}}, "parents": [{"sha": "x"}]},
              {"sha": "b", "commit": {"committer": {"date": "2026-11-12T10:04:00Z"}}, "parents": [{"sha": "a"}]},
              {"sha": "c", "commit": {"committer": {"date": "2026-11-12T10:30:00Z"}}, "parents": [{"sha": "b"}]}
            ]
        """
        assertEquals(2, SyncLogic.parseCommits(burst, "me/alpha").size)
        assertEquals(t("2026-11-11T10:00:00Z"), events[0].time)
    }

    @Test fun ciConclusion() {
        assertEquals(true, SyncLogic.parseCiFailing("""{"workflow_runs": [{"conclusion": "failure"}]}"""))
        assertEquals(false, SyncLogic.parseCiFailing("""{"workflow_runs": [{"conclusion": "success"}]}"""))
        assertNull(SyncLogic.parseCiFailing("""{"workflow_runs": []}"""))
        assertNull(SyncLogic.parseCiFailing("""{"total_count": 0}"""))
    }

    @Test fun usernames() {
        assertTrue(SyncLogic.validUsername("Maneek21"))
        assertTrue(SyncLogic.validUsername("a-b"))
        assertFalse(SyncLogic.validUsername(""))
        assertFalse(SyncLogic.validUsername("-bad"))
        assertFalse(SyncLogic.validUsername("has space"))
        assertFalse(SyncLogic.validUsername("a/b?x=1"))
    }

    @Test fun newEventsAreFilteredAndSorted() {
        val events = listOf(ShipEvent(30, false, "r"), ShipEvent(10, false, "r"), ShipEvent(20, true, "r"))
        val out = SyncLogic.newEvents(events, 10)
        assertEquals(listOf(20L, 30L), out.map { it.time })
    }

    @Test fun firstSyncTakesTheLatestUnbrokenRun() {
        val now = 1000 * h
        val events = listOf(
            ShipEvent(now - 400 * h, false, "r"),   // long ago, before a gap
            ShipEvent(now - 100 * h, false, "r"),
            ShipEvent(now - 70 * h, false, "r"),
            ShipEvent(now - 30 * h, false, "r"),
            ShipEvent(now - 2 * h, true, "r")
        )
        val run = SyncLogic.firstSyncWindow(events, now)
        assertEquals(listOf(now - 100 * h, now - 70 * h, now - 30 * h, now - 2 * h), run.map { it.time })
    }

    @Test fun firstSyncGivesNothingWhenTheLastShipIsTooOld() {
        val now = 1000 * h
        assertTrue(SyncLogic.firstSyncWindow(listOf(ShipEvent(now - 48 * h, false, "r")), now).isEmpty())
        assertEquals(1, SyncLogic.firstSyncWindow(listOf(ShipEvent(now - 47 * h, false, "r")), now).size)
        assertTrue(SyncLogic.firstSyncWindow(emptyList(), now).isEmpty())
    }

    @Test
    fun firstSyncReplaysHistoryAndHatchesTheStarter() {
        val g = Game.fresh(now)
        Engine.pickStarter(g, "mossback")
        val events = listOf(ShipEvent(now - 30 * h, false, "a/b"), ShipEvent(now - 2 * h, true, "a/b"))
        val summary = SyncLogic.apply(g, events, 0, backdate = true)
        assertEquals(2, summary.fed)
        assertEquals(1, summary.merges)
        assertNotNull(summary.hatched)
        assertEquals(1, summary.gems)
        assertEquals(1, g.party.size)
        // a month of merges still starts with a small purse
        val rich = Game.fresh(now)
        Engine.pickStarter(rich, "mossback")
        SyncLogic.apply(rich, (1..20).map { ShipEvent(now - 40 * h + it * h, true, "a/b") }, 0, backdate = true)
        assertEquals(3, rich.gems)
        assertEquals("Found your recent work. Mossback hatched with a ${g.streak}-day streak.", SyncLogic.message(summary, g, true))
    }

    @Test
    fun eventsAlreadyCountedAreIgnored() {
        val g = Game.fresh(0)
        Engine.pickStarter(g, "mossback")
        Engine.ship(g, 10 * h, false, 0)
        val summary = SyncLogic.apply(g, listOf(ShipEvent(5 * h, false, "r"), ShipEvent(10 * h, false, "r"), ShipEvent(12 * h, true, "r")), 0, backdate = false)
        assertEquals(1, summary.fed)
        assertEquals(1, summary.merges)
    }

    @Test
    fun nothingIsAppliedBeforeAStarterIsPicked() {
        val g = Game.fresh(0)
        val summary = SyncLogic.apply(g, listOf(ShipEvent(h, false, "r")), 0, backdate = false)
        assertEquals(0, summary.fed)
        assertEquals("Pick a starter egg first.", SyncLogic.message(summary, g, false))
    }

    @Test
    fun messagesReadWell() {
        val g = Game.fresh(0)
        Engine.pickStarter(g, "mossback")
        val none = SyncLogic.apply(g, emptyList(), 0, backdate = true)
        assertEquals("No ships in the last 48 hours. The egg hatches on your next push.", SyncLogic.message(none, g, true))
        assertEquals("Up to date. Nothing new.", SyncLogic.message(none, g, false))
        val two = SyncLogic.apply(g, listOf(ShipEvent(h, false, "r"), ShipEvent(2 * h, false, "r")), 0, backdate = false)
        assertEquals("Mossback hatched.", SyncLogic.message(two, g, false))
        val one = SyncLogic.apply(g, listOf(ShipEvent(3 * h, false, "r")), 0, backdate = false)
        assertEquals("1 new ship. The horde ate.", SyncLogic.message(one, g, false))
    }
}
