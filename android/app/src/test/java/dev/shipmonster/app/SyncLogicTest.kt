package dev.shipmonster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncLogicTest {
    private val h = PetEngine.H
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
        assertTrue(events[0].merge)
        assertFalse(events[1].merge)
        assertEquals(t("2026-11-11T10:00:00Z"), events[1].time)
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

    @Test fun firstSyncBackdatesTheEggAndBuildsARealStreak() {
        val now = 100 * 24 * h + 12 * h
        val s = PetEngine.newState(now)
        val events = (3 downTo 0).map { ShipEvent(now - it * 24 * h - h, false, "r") }
        val summary = SyncLogic.apply(s, events, 0, backdate = true)
        assertEquals(4, summary.fed)
        assertTrue(summary.hatched)
        assertEquals(4, s.pet.streak)
        assertEquals(Status.HAPPY, PetEngine.status(s, now))
        assertEquals(now - 3 * 24 * h - h, s.pet.hatchedAt)
    }

    @Test fun withoutBackdatingOldEventsDoNotHatchANewEgg() {
        val now = 100 * 24 * h
        val s = PetEngine.newState(now)
        val summary = SyncLogic.apply(s, listOf(ShipEvent(now - h, false, "r")), 0, backdate = false)
        assertEquals(0, summary.fed)
        assertEquals(Status.EGG, PetEngine.status(s, now))
        val later = SyncLogic.apply(s, listOf(ShipEvent(now + h, false, "r")), 0, backdate = false)
        assertEquals(1, later.fed)
        assertTrue(later.hatched)
    }

    @Test fun eventsOlderThanTheLastMealAreIgnored() {
        val s = PetEngine.newState(0)
        PetEngine.ship(s, 10 * h, false)
        val summary = SyncLogic.apply(s, listOf(ShipEvent(5 * h, false, "r"), ShipEvent(10 * h, false, "r"), ShipEvent(12 * h, true, "r")), 0, backdate = false)
        assertEquals(1, summary.fed)
        assertEquals(1, summary.merges)
        assertEquals(12 * h, s.pet.lastFedAt)
    }

    @Test fun aShipTurnsAMonsterBackAndADeadPetStaysDead() {
        val s = PetEngine.newState(0)
        PetEngine.ship(s, 0, false)
        val back = SyncLogic.apply(s, listOf(ShipEvent(60 * h, false, "r")), 0, backdate = false)
        assertTrue(back.turnedBack)
        assertEquals(Status.HAPPY, PetEngine.status(s, 60 * h))

        // Next ship comes 130 hours later: the pet starved at hour 120 of that gap.
        val late = SyncLogic.apply(s, listOf(ShipEvent(190 * h, false, "r"), ShipEvent(191 * h, false, "r")), 0, backdate = false)
        assertEquals(0, late.fed)
        assertEquals(Cause.STARVED, s.pet.deadCause)
        assertEquals(1, s.graveyard.size)
    }

    @Test fun messages() {
        val s = PetEngine.newState(0)
        assertEquals("No ships in the last 48 hours. Ship something to hatch the egg.", SyncLogic.message(ApplySummary(), s, true))
        assertEquals("Up to date. Nothing new.", SyncLogic.message(ApplySummary(), s, false))
        PetEngine.ship(s, 0, false)
        assertEquals("Found your recent work. Pip hatched with a 1-day streak.", SyncLogic.message(ApplySummary(fed = 1, hatched = true), s, true))
        assertEquals("2 new ships. Pip ate.", SyncLogic.message(ApplySummary(fed = 2), s, false))
        assertEquals("Pip turned back. Apps unlocked.", SyncLogic.message(ApplySummary(fed = 1, turnedBack = true), s, false))
    }

    @Test fun agoText() {
        assertEquals("just now", PetText.ago(20_000))
        assertEquals("12 min ago", PetText.ago(12 * 60_000L))
        assertEquals("3h ago", PetText.ago(3 * h + 5 * 60_000))
        assertEquals("2d ago", PetText.ago(50 * h))
    }
}
