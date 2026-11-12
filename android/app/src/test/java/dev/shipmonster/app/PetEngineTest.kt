package dev.shipmonster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as simulator/test_engine.js, so the Kotlin and JavaScript rules cannot drift apart unnoticed. */
class PetEngineTest {
    private val h = PetEngine.H
    private val t0 = 9 * h

    private fun fresh() = PetEngine.newState(t0)
    private fun hatched(): PetState {
        val s = fresh()
        PetEngine.ship(s, t0, false)
        return s
    }

    private fun at(hours: Double): Long = t0 + (hours * h).toLong()

    @Test fun eggNeverStarves() {
        val s = fresh()
        assertEquals(Status.EGG, PetEngine.status(s, at(24.0 * 30)))
        assertFalse(PetEngine.tick(s, at(24.0 * 30)))
        assertTrue(s.graveyard.isEmpty())
    }

    @Test fun firstCommitHatchesASprout() {
        val s = fresh()
        val r = PetEngine.ship(s, t0, false)
        assertNotNull(r)
        assertTrue(r!!.hatched)
        assertEquals(Status.HAPPY, PetEngine.status(s, t0))
        assertEquals(Form.SPROUT, s.pet.form)
        assertEquals(1, s.pet.streak)
    }

    @Test fun thresholds() {
        val s = hatched()
        assertEquals(Status.HAPPY, PetEngine.status(s, at(23.9)))
        assertEquals(Status.HUNGRY, PetEngine.status(s, at(24.0)))
        assertEquals(Status.HUNGRY, PetEngine.status(s, at(47.9)))
        assertEquals(Status.MONSTER, PetEngine.status(s, at(48.0)))
        assertEquals(Status.MONSTER, PetEngine.status(s, at(119.9)))
        assertEquals(Status.DEAD, PetEngine.status(s, at(120.0)))
    }

    @Test fun starvationIsRecordedAtTheExactHour() {
        val s = hatched()
        assertTrue(PetEngine.tick(s, at(700.0)))
        assertEquals(Cause.STARVED, s.pet.deadCause)
        assertEquals(t0 + 120 * h, s.pet.deadAt)
        assertEquals(1, s.graveyard.size)
        assertEquals(5, s.graveyard[0].days)
        assertFalse(PetEngine.tick(s, at(800.0)))
        assertEquals(1, s.graveyard.size)
    }

    @Test fun commitTurnsAMonsterBack() {
        val s = hatched()
        val r = PetEngine.ship(s, at(60.0), false)
        assertEquals(Status.MONSTER, r!!.was)
        assertEquals(Status.HAPPY, PetEngine.status(s, at(60.0)))
        assertEquals(1.0, PetEngine.fullness(s, at(60.0)), 0.0001)
    }

    @Test fun mercyOnlyWorksOnAMonster() {
        val s = hatched()
        assertFalse(PetEngine.mercy(s, at(1.0)))
        assertFalse(PetEngine.mercy(s, at(30.0)))
        assertTrue(PetEngine.mercy(s, at(60.0)))
        assertEquals(Status.DEAD, PetEngine.status(s, at(60.0)))
        assertEquals(Cause.MERCY, s.graveyard[0].cause)
    }

    @Test fun deadPetsIgnoreShips() {
        val s = hatched()
        assertNull(PetEngine.ship(s, at(200.0), false))
        assertFalse(PetEngine.mercy(s, at(200.0)))
        assertEquals(1, s.graveyard.size)
    }

    @Test fun streakCountsConsecutiveDays() {
        val s = hatched()
        PetEngine.ship(s, at(1.0), false)
        assertEquals(1, s.pet.streak)
        PetEngine.ship(s, at(24.0), false)
        assertEquals(2, s.pet.streak)
        PetEngine.ship(s, at(48.0), true)
        assertEquals(3, s.pet.streak)
    }

    @Test fun skippedDayResetsStreakButKeepsBest() {
        val s = hatched()
        PetEngine.ship(s, at(24.0), false)
        PetEngine.ship(s, at(74.0), false)
        assertEquals(1, s.pet.streak)
        assertEquals(2, s.pet.bestStreak)
    }

    @Test fun streakFollowsTheLocalDay() {
        val tz = (5.5 * h).toLong()
        val s = PetEngine.newState(0)
        // 18:00 UTC is 23:30 in India, 19:00 UTC is 00:30 the next day there.
        PetEngine.ship(s, 18 * h, false, tz)
        PetEngine.ship(s, 19 * h, false, tz)
        assertEquals(2, s.pet.streak)
    }

    @Test fun evolvesAtSevenAndThirtyAndNeverRegresses() {
        val s = hatched()
        var evolved: Form? = null
        for (i in 1..6) evolved = PetEngine.ship(s, at(24.0 * i), false)!!.evolved
        assertEquals(7, s.pet.streak)
        assertEquals(Form.LEAFLING, s.pet.form)
        assertEquals(Form.LEAFLING, evolved)
        val gap = 24.0 * 6 + 60
        PetEngine.ship(s, at(gap), false)
        assertEquals(1, s.pet.streak)
        assertEquals(Form.LEAFLING, s.pet.form)
        for (i in 1..29) PetEngine.ship(s, at(gap + 24.0 * i), false)
        assertEquals(30, s.pet.streak)
        assertEquals(Form.BLOOM, s.pet.form)
    }

    @Test fun hatchingNeedsADeadPetAndRotatesTheName() {
        val s = hatched()
        assertFalse(PetEngine.hatchNew(s, at(10.0)))
        assertTrue(PetEngine.hatchNew(s, at(130.0)))
        assertEquals(Status.EGG, PetEngine.status(s, at(130.0)))
        assertEquals("Mochi", s.pet.name)
        assertEquals("Pip", s.graveyard[0].name)
    }

    @Test fun fullnessAndCountdown() {
        val s = hatched()
        assertEquals(0.75, PetEngine.fullness(s, at(12.0)), 0.0001)
        assertEquals(12.0, PetEngine.hoursToNext(s, at(12.0))!!, 0.0001)
        assertEquals(0.25, PetEngine.fullness(s, at(36.0)), 0.0001)
        assertEquals(0.0, PetEngine.fullness(s, at(60.0)), 0.0001)
        assertEquals(60.0, PetEngine.hoursToNext(s, at(60.0))!!, 0.0001)
    }

    @Test fun epitaphs() {
        assertEquals(
            "R.I.P. Pip. Lived 9 days. Starved after 72 hours as a monster.",
            PetEngine.epitaph(Grave("Pip", Form.SPROUT, Cause.STARVED, 0, 9, 3, 4))
        )
        assertEquals(
            "R.I.P. Fig. Lived less than a day. Mercy-unlocked so its owner could scroll.",
            PetEngine.epitaph(Grave("Fig", Form.SPROUT, Cause.MERCY, 0, 0, 1, 1))
        )
    }

    @Test fun spriteGridsAreSixteenBySixteenWithKnownColours() {
        for ((name, grid) in SpriteData.ALL) {
            assertEquals("$name rows", 16, grid.size)
            for (row in grid) {
                assertEquals("$name row width", 16, row.length)
                for (ch in row) if (ch != '.') assertTrue("$name colour $ch", SpriteData.PALETTE.containsKey(ch))
            }
        }
    }

    @Test fun durations() {
        assertEquals("under 1h", PetText.dur(0.4))
        assertEquals("5h", PetText.dur(5.2))
        assertEquals("2d 5h", PetText.dur(53.0))
        assertEquals("3d", PetText.dur(72.0))
    }
}
