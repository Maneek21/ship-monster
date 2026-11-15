package dev.shipmonster.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val h = Rules.H
    private val d = Rules.D
    private val mon = 4 * d          // 1970-01-05, a Monday, in UTC

    private fun started(species: String = "mossback", at: Long = mon + 12 * h): Game {
        val g = Game.fresh(mon)
        assertTrue(Engine.pickStarter(g, species))
        Engine.ship(g, at, false, 0)
        return g
    }

    private fun add(g: Game, species: String, party: Boolean = true): Monster {
        val m = Monster(g.nextId++, species, Dex.get(species).name, bornAt = g.lastTick)
        g.monsters.add(m)
        if (party) g.party.add(m.id)
        return m
    }

    @Test
    fun calendarHelpers() {
        assertEquals(1, Rules.dow(4))
        assertEquals(6, Rules.dow(9))
        assertEquals(0, Rules.dow(10))
        assertEquals(Rules.week(4), Rules.week(10))
        assertTrue(Rules.week(11) > Rules.week(10))
        assertEquals(Flavor.DAWN, Rules.flavorAt(5))
        assertEquals(Flavor.DAY, Rules.flavorAt(16))
        assertEquals(Flavor.DUSK, Rules.flavorAt(22))
        assertEquals(Flavor.MIDNIGHT, Rules.flavorAt(23))
        assertEquals(Flavor.MIDNIGHT, Rules.flavorAt(4))
        assertEquals(1, Rules.stageFor(0.0))
        assertEquals(2, Rules.stageFor(30.0))
        assertEquals(8, Rules.stageFor(5000.0))
    }

    @Test
    fun aNewGameNeedsAStarterAndTheEggHatchesOnTheFirstShip() {
        val g = Game.fresh(mon)
        assertTrue(g.needsStarter)
        assertFalse(Engine.pickStarter(g, "mergewyrm"))
        assertTrue(Engine.pickStarter(g, "cindermite"))
        assertEquals(Mood.EMPTY, Engine.mood(g))
        Engine.advance(g, mon + 10 * d, 0)
        assertEquals(Mood.EMPTY, Engine.mood(g))
        val evs = Engine.ship(g, mon + 10 * d + h, false, 0)
        assertTrue(evs.any { it.kind == Ev.HATCHED })
        assertEquals("cindermite", g.lead!!.species)
        assertEquals(1, g.lead!!.stage)
        assertEquals(Mood.CALM, Engine.mood(g))
        assertEquals(1, g.streak)
    }

    @Test
    fun pushesPayLessAsTheDayGoesOn() {
        val g = started()
        g.lifetimeChow = 300            // level 4, so the pantry is big enough
        repeat(6) { Engine.ship(g, mon + 12 * h + (it + 1) * 60_000L, false, 0) }
        assertEquals(7, g.pushesToday)
        // 10 + 10 + 10 + 5 + 5 + 5 + 2, none eaten yet because the belly is full
        assertEquals(47, g.pantry)
        Engine.ship(g, mon + d + 12 * h, false, 0)
        assertEquals(1, g.pushesToday)
    }

    @Test
    fun chowCarriesTheFlavourOfTheHour() {
        val g = started(at = mon + 8 * h)
        assertEquals(10, g.chow[Flavor.DAWN.ordinal])
        Engine.ship(g, mon + 23 * h + 30 * 60_000, false, 0)
        assertEquals(10, g.chow[Flavor.MIDNIGHT.ordinal])
    }

    @Test
    fun mergesPayGemsUpToThreeADay() {
        val g = started()
        repeat(5) { Engine.ship(g, mon + 13 * h + it * 60_000L, true, 0) }
        assertEquals(3, g.gems - (g.level - 1))
        assertEquals(5, g.merges)
        Engine.ship(g, mon + d + 13 * h, true, 0)
        assertEquals(4, g.gems - (g.level - 1))
    }

    @Test
    fun restlessAtOneDayFeralAtTwo() {
        val g = started()
        val t0 = mon + 12 * h
        Engine.advance(g, t0 + 23 * h, 0)
        assertEquals(Mood.CALM, Engine.mood(g))
        assertEquals(1, Engine.hoursToNext(g))
        val evs = Engine.advance(g, t0 + 24 * h, 0)
        assertTrue(evs.any { it.kind == Ev.RESTLESS })
        assertEquals(Mood.RESTLESS, Engine.mood(g))
        assertFalse(Engine.lockActive(g, t0 + 24 * h))
        val evs2 = Engine.advance(g, t0 + 48 * h, 0)
        assertTrue(evs2.any { it.kind == Ev.FERAL })
        assertEquals(Mood.FERAL, Engine.mood(g))
        assertTrue(Engine.lockActive(g, t0 + 48 * h))
        assertEquals(0, g.streak)
        assertEquals(72, Engine.hoursToNext(g))
    }

    @Test
    fun goingFeralDropsEveryPartyMonsterOneStage() {
        val g = started()
        g.lead!!.gp = 150.0                      // a Brute, stage 4
        val den = add(g, "gravelkin", party = false)
        den.gp = 150.0
        val evs = Engine.advance(g, mon + 12 * h + 48 * h, 0)
        assertTrue(evs.any { it.kind == Ev.DROPPED })
        assertEquals(3, g.lead!!.stage)
        assertEquals(70.0, g.lead!!.gp, 0.001)
        assertEquals(4, den.stage)
    }

    @Test
    fun aShipCalmsAFeralHordeAtOnce() {
        val g = started()
        val t = mon + 12 * h + 50 * h
        Engine.advance(g, t, 0)
        assertTrue(Engine.lockActive(g, t))
        val evs = Engine.ship(g, t + 60_000, false, 0)
        assertTrue(evs.any { it.kind == Ev.CALMED })
        assertEquals(Mood.CALM, Engine.mood(g))
        assertFalse(Engine.lockActive(g, t + 60_000))
        assertEquals(1, g.streak)
    }

    @Test
    fun theHordeFallsAfterThreeDaysFeral() {
        val g = started()
        val rare = add(g, "gloomfang")
        val resting = add(g, "fuzzlet", party = false)
        g.restDays.clear()
        val evs = Engine.advance(g, mon + 12 * h + 120 * h, 0)
        assertTrue(evs.any { it.kind == Ev.FALL })
        assertNull(g.owned("mossback"))          // the common is gone for good
        assertTrue(rare.stone)                   // the rare is a statue
        assertEquals(listOf(resting.id), g.party) // the den monster steps up
        assertEquals(Mood.CALM, Engine.mood(g))
        assertEquals(1, g.falls)
        assertEquals(1, g.hall.size)
    }

    @Test
    fun aFallWithNobodyLeftAsksForANewStarter() {
        val g = started()
        g.restDays.clear()
        Engine.advance(g, mon + 12 * h + 121 * h, 0)
        assertTrue(g.needsStarter)
        assertEquals(Mood.EMPTY, Engine.mood(g))
        assertTrue(Engine.pickStarter(g, "puddlewump"))
        Engine.ship(g, mon + 12 * h + 122 * h, false, 0)
        assertEquals("puddlewump", g.lead!!.species)
    }

    @Test
    fun restDaysPauseTheClock() {
        val fri = mon + 4 * d
        val g = Game.fresh(mon)
        Engine.pickStarter(g, "mossback")
        Engine.ship(g, fri + 12 * h, false, 0)
        Engine.advance(g, fri + 3 * d + 11 * h, 0)   // Monday 11:00: 12 awake hours on Friday, 11 on Monday
        assertEquals(23 * h, g.idleMs)
        assertEquals(Mood.CALM, Engine.mood(g))
        assertTrue(Engine.isRest(g, fri + d + h, 0))
        assertFalse(Engine.isRest(g, fri + 3 * d + h, 0))
    }

    @Test
    fun aStreakCarriesAcrossRestDaysButNotAcrossASkippedWorkday() {
        val fri = mon + 4 * d
        val g = Game.fresh(mon)
        Engine.pickStarter(g, "mossback")
        Engine.ship(g, mon + 3 * d + 12 * h, false, 0)       // Thursday
        Engine.ship(g, fri + 12 * h, false, 0)
        assertEquals(2, g.streak)
        Engine.ship(g, fri + 3 * d + 9 * h, false, 0)        // Monday, after the weekend
        assertEquals(3, g.streak)
        Engine.ship(g, fri + 5 * d + 8 * h, false, 0)        // Wednesday, Tuesday skipped, 47 hours later
        assertEquals(1, g.streak)
        assertEquals(3, g.bestStreak)
        assertEquals(Mood.CALM, Engine.mood(g))
    }

    @Test
    fun wellFedDaysGrowTheMonster() {
        val g = started()                                     // Monday noon, Day chow
        Engine.ship(g, mon + d + 12 * h, false, 0)
        Engine.ship(g, mon + 2 * d + 12 * h, false, 0)
        Engine.advance(g, mon + 3 * d + 60_000, 0)            // three midnights
        assertEquals(30.0, g.lead!!.gp, 0.001)
        assertEquals(2, g.lead!!.stage)
        assertTrue(g.log.any { it.kind == "evolve" })
    }

    @Test
    fun favouriteFlavourAddsGrowth() {
        val g = started(at = mon + 8 * h)                     // Dawn chow, a Mossback's favourite
        Engine.advance(g, mon + 20 * h, 0)                    // long enough to get hungry and graze
        assertTrue(g.lead!!.ateFav)
        Engine.advance(g, mon + d + 60_000, 0)
        assertEquals(12.0, g.lead!!.gp, 0.001)
        assertFalse(g.lead!!.ateFav)
    }

    @Test
    fun starsSpeedUpGrowthAndNothingGrowsOnARestDay() {
        val g = started()
        g.lead!!.stars = 5
        Engine.advance(g, mon + d + 60_000, 0)
        assertEquals(15.0, g.lead!!.gp, 0.001)
        val sat = mon + 5 * d
        val before = Engine.advance(g, sat, 0).size
        val gp = g.lead!!.gp
        Engine.advance(g, sat + d + 60_000, 0)
        assertEquals(gp, g.lead!!.gp, 0.001)
        assertTrue(before >= 0)
    }

    @Test
    fun monstersGrazeByAppetiteAndTheDenDoesNotEat() {
        val g = started()
        val big = add(g, "mergewyrm")
        val resting = add(g, "fuzzlet", party = false)
        resting.belly = 40.0
        g.chow[Flavor.DAY.ordinal] = 60
        Engine.advance(g, mon + 12 * h + 12 * h, 0)           // half an awake day
        // a common needs 5 a day and a legendary 16, so half a day costs about 2 and 8
        val eaten = 60 - g.pantry
        assertTrue("ate $eaten", eaten in 9..12)
        assertTrue(g.lead!!.belly > 90)
        assertTrue(big.belly > 90)
        assertEquals(40.0, resting.belly, 0.001)
    }

    @Test
    fun aStarvingExtraMonsterSulksOffButTheLastOneStays() {
        val g = started()
        g.restDays.clear()
        val extra = add(g, "gravelkin")
        extra.belly = 0.0
        g.chow.fill(0)
        g.lead!!.belly = 0.0
        Engine.ship(g, mon + 13 * h, false, 0)                // 10 chow: the lead eats first
        g.chow.fill(0)
        extra.belly = 0.0
        val evs = Engine.advance(g, mon + 13 * h + 25 * h, 0)
        assertTrue(evs.any { it.kind == Ev.BENCHED && it.monster === extra })
        assertEquals(1, g.party.size)
        assertFalse(extra.stone)
        // alone and starving, the lead still stays
        Engine.advance(g, mon + 13 * h + 47 * h, 0)
        assertEquals(1, g.party.size)
    }

    @Test
    fun treats() {
        val g = started()
        val id = g.lead!!.id
        g.lead!!.belly = 50.0
        g.chow.fill(0)
        assertEquals(-3, Engine.treat(g, id, mon + 13 * h))
        g.chow[Flavor.DAY.ordinal] = 20
        assertEquals(1, Engine.treat(g, id, mon + 13 * h))
        assertEquals(60.0, g.lead!!.belly, 0.001)
        assertEquals(1.0, g.lead!!.gp, 0.001)
        g.chow[Flavor.DAWN.ordinal] = 4
        assertEquals(2, Engine.treat(g, id, mon + 13 * h))
        assertEquals(3.0, g.lead!!.gp, 0.001)
        assertEquals(2, Engine.treat(g, id, mon + 13 * h))
        assertEquals(-2, Engine.treat(g, id, mon + 13 * h))
        assertEquals(-1, Engine.treat(g, 999, mon + 13 * h))
        Engine.advance(g, mon + d + 60_000, 0)
        assertEquals(0, g.lead!!.treats)
    }

    @Test
    fun aFeastFillsBelliesAndWakesStatues() {
        val g = started()
        val statue = add(g, "brasshorn", party = false)
        statue.stone = true
        g.lead!!.belly = 5.0
        assertNull(Engine.feast(g, mon + 13 * h))
        g.gems = 2
        g.lifetimeChow = 60                                  // level 2: two party slots
        assertNotNull(Engine.feast(g, mon + 13 * h))
        assertEquals(0, g.gems)
        assertEquals(100.0, g.lead!!.belly, 0.001)
        assertEquals(10.0, g.lead!!.gp, 0.001)
        assertFalse(statue.stone)
        assertTrue(statue.id in g.party)
    }

    @Test
    fun gemsReviveAndStockUp() {
        val g = started()
        val statue = add(g, "brasshorn", party = false)
        statue.stone = true
        assertFalse(Engine.revive(g, statue.id, mon + 13 * h))
        g.gems = 2
        assertTrue(Engine.revive(g, statue.id, mon + 13 * h))
        assertFalse(statue.stone)
        val before = g.pantry
        assertTrue(Engine.stockUp(g, mon + 13 * h, 0))
        assertEquals(before + 30, g.pantry)
        assertEquals(0, g.gems)
        assertFalse(Engine.stockUp(g, mon + 13 * h, 0))
    }

    @Test
    fun thePantryHasACapAndOverflowFeedsGrowth() {
        val g = started()
        g.chow[Flavor.DAY.ordinal] = g.pantryCap - 5
        Engine.ship(g, mon + 13 * h, true, 0)
        assertEquals(g.pantryCap, g.pantry)
        assertEquals(0.5, g.lead!!.gp, 0.001)
    }

    @Test
    fun levelsPayGemsAndOpenPartySlots() {
        val g = started()
        assertEquals(1, g.level)
        assertEquals(1, g.slots)
        repeat(4) { Engine.ship(g, mon + (it + 1) * d + 12 * h, false, 0) }
        assertEquals(50, g.lifetimeChow)
        assertEquals(2, g.level)
        assertEquals(2, g.slots)
        assertTrue(g.gems >= 1)
        assertEquals(10, Rules.levelFor(99999))
        assertEquals(5, Rules.slots(10))
    }

    @Test
    fun streakMilestonesAndTheWeeklyBountyPayGems() {
        val g = Game.fresh(mon)
        Engine.pickStarter(g, "mossback")
        val all = ArrayList<Event>()
        for (day in listOf(0, 1, 2, 3, 4, 7, 8)) all.addAll(Engine.ship(g, mon + day * d + 12 * h, false, 0))
        assertEquals(7, g.streak)
        assertTrue(all.any { it.kind == Ev.MILESTONE && it.n == 7 })
        assertTrue(all.any { it.kind == Ev.BOUNTY })
        assertEquals(1, all.count { it.kind == Ev.BOUNTY })
    }

    @Test
    fun eggsRespectThePityRules() {
        val g = started()
        g.gems = 200
        var sinceRare = 0
        var legendary = false
        repeat(120) {
            val egg = Engine.buyEgg(g, mon + 13 * h)!!
            val r = Dex.get(egg.species).rarity
            if (r == Rarity.COMMON) sinceRare++ else sinceRare = 0
            assertTrue("five commons in a row", sinceRare < 5)
            if (r == Rarity.LEGENDARY) legendary = true
        }
        assertTrue(legendary)
        assertEquals(80, g.gems)
        assertEquals(120, g.nest.size)
        g.gems = 0
        assertNull(Engine.buyEgg(g, mon + 13 * h))
    }

    @Test
    fun threeTapsCrackAnEggAndTwinsBecomeStars() {
        val g = started()
        g.nest.add(Egg("gloomfang"))
        assertNull(Engine.tapEgg(g, 0, mon + 13 * h))
        assertNull(Engine.tapEgg(g, 0, mon + 13 * h))
        val first = Engine.tapEgg(g, 0, mon + 13 * h)!!
        assertTrue(first.isNew)
        assertEquals(0, g.nest.size)
        assertFalse(first.monster.id in g.party)          // level 1 has one slot, so it waits in the den
        assertTrue("gloomfang" in g.seen)
        first.monster.stone = true
        g.nest.add(Egg("gloomfang", taps = 2))
        val twin = Engine.tapEgg(g, 0, mon + 13 * h)!!
        assertFalse(twin.isNew)
        assertTrue(twin.star)
        assertTrue(twin.revived)
        assertEquals(1, first.monster.stars)
        assertEquals(2, g.monsters.size)
        first.monster.stars = 5
        g.nest.add(Egg("gloomfang", taps = 2))
        val maxed = Engine.tapEgg(g, 0, mon + 13 * h)!!
        assertEquals(30, maxed.chow)
    }

    @Test
    fun aTruceOpensTheLockOncePerFeralSpell() {
        val g = started()
        val t = mon + 12 * h + 49 * h
        Engine.advance(g, t, 0)
        assertFalse(Engine.truce(g, t))                   // no gem
        g.gems = 2
        assertTrue(Engine.truce(g, t))
        assertFalse(Engine.lockActive(g, t + 11 * h))
        assertTrue(Engine.lockActive(g, t + 12 * h))
        assertFalse(Engine.truce(g, t + 13 * h))
        assertEquals(1, g.gems)
        assertTrue(Engine.nextWakeAt(g, t, 0) <= t + 12 * h + 1000)
    }

    @Test
    fun surrenderMakesTheHordeFallNow() {
        val g = started()
        assertTrue(Engine.surrender(g, mon + 13 * h).isEmpty())   // only while feral
        val t = mon + 12 * h + 49 * h
        Engine.advance(g, t, 0)
        val evs = Engine.surrender(g, t)
        assertTrue(evs.any { it.kind == Ev.FALL })
        assertTrue(g.needsStarter)
    }

    @Test
    fun hibernationPausesEverything() {
        val g = started()
        val t = mon + 13 * h
        assertFalse(Engine.hibernate(g, t, 15))
        assertTrue(Engine.hibernate(g, t, 7))
        Engine.advance(g, t + 6 * d, 0)
        assertEquals(h, g.idleMs)
        assertEquals(Mood.CALM, Engine.mood(g))
        Engine.wake(g, t + 6 * d)
        assertFalse(Engine.hibernating(g, t + 6 * d + 1))
        assertFalse(Engine.hibernate(g, t + 7 * d, 3))            // cooldown
        assertTrue(Engine.hibernate(g, t + 40 * d, 3))
    }

    @Test
    fun partyManagement() {
        val g = started()
        g.lifetimeChow = 60
        val b = add(g, "gravelkin", party = false)
        assertTrue(Engine.toParty(g, b.id))
        assertFalse(Engine.toParty(g, b.id))
        assertTrue(Engine.makeLead(g, b.id))
        assertEquals(b.id, g.party[0])
        assertTrue(Engine.toDen(g, b.id))
        assertFalse(Engine.toDen(g, g.party[0]))                  // the last one stays
        assertTrue(Engine.rename(g, b.id, "  Rocky  "))
        assertEquals("Rocky", b.name)
        assertFalse(Engine.rename(g, b.id, "   "))
    }

    @Test
    fun theGameSurvivesASaveAndLoad() {
        val g = started()
        add(g, "voidling").stars = 2
        g.nest.add(Egg("sunmaw", 1))
        g.gems = 4
        g.restDays.clear(); g.restDays.add(3)
        Engine.advance(g, mon + 2 * d, 0)
        val back = Game.fromJson(g.toJson())
        assertEquals(g.toJson(), back.toJson())
        assertEquals(2, back.monsters.size)
        assertEquals(2, back.owned("voidling")!!.stars)
        assertEquals(setOf(3), back.restDays)
        assertEquals(g.idleMs, back.idleMs)
        assertEquals(g.log.size, back.log.size)
    }

    @Test
    fun aFirstSyncCanReplayHistoryFromBeforeTheGameExisted() {
        val g = Game.fresh(mon + 20 * d)
        Engine.pickStarter(g, "mossback")
        Engine.rewind(g, mon + 14 * d)
        for (day in 14..18) Engine.ship(g, mon + day * d + 12 * h, false, 0)
        Engine.advance(g, mon + 20 * d, 0)
        assertEquals(5, g.streak)
        assertTrue(g.lead!!.gp >= 40.0)
        assertEquals(Mood.CALM, Engine.mood(g))
    }

    @Test
    fun titansGetTheirEpithetAndAPlaceInTheHall() {
        val g = started()
        g.lead!!.gp = 1795.0
        Engine.advance(g, mon + d + 60_000, 0)
        assertEquals(8, g.lead!!.stage)
        assertEquals("Mossback the Evergreen", g.lead!!.title)
        assertTrue(g.hall.any { it.kind == "titan" })
        assertEquals(1f, Rules.stageProgress(g.lead!!.gp), 0.001f)
    }
}
