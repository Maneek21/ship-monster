package dev.shipmonster.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinesTest {
    private val bank = LineBank(File("src/main/assets/lines.tsv").readText())
    private val mon = 4 * Rules.D

    private fun game(species: String = "mossback"): Game {
        val g = Game.fresh(mon)
        Engine.pickStarter(g, species)
        Engine.ship(g, mon + 12 * Rules.H, false, 0)
        return g
    }

    @Test
    fun theBankIsBigAndClean() {
        assertTrue("only ${bank.lines.size} lines", bank.lines.size >= 250)
        val known = setOf("{name}", "{streak}", "{hours}", "{next}", "{chow}", "{stage}", "{app}")
        for (l in bank.lines) {
            assertFalse("em dash in: ${l.text}", l.text.contains('—'))
            assertTrue("too long: ${l.text}", l.text.length <= 90)
            for (v in Regex("\\{[a-z]+}").findAll(l.text)) assertTrue("unknown ${v.value} in: ${l.text}", v.value in known)
        }
    }

    @Test
    fun everySituationTheAppAsksForHasALineForEveryVoice() {
        val used = listOf("fed", "hungry", "restless", "feral", "restday", "hibernate", "night", "morning", "streak",
            "cired", "pantrylow", "egg", "fedpush", "merged", "hatched", "evolved", "dropped", "treat", "treatfav",
            "treatnone", "feast", "calmed", "milestone", "levelup", "benched", "revived", "truce", "newegg",
            "star", "poke", "lock")
        for (s in used) {
            assertTrue("no lines for $s", bank.has(s))
            for (sp in Dex.all) {
                val g = game(if (sp.id in Dex.starters) sp.id else "mossback")
                val m = Monster(99, sp.id, sp.name)
                val said = bank.say(s, m, g, 7)
                assertTrue("$s / ${sp.voice}: '$said'", said.length > 2 || s == "egg")
            }
        }
    }

    @Test
    fun sizeChangesTheVoice() {
        val g = game()
        val m = g.lead!!
        val small = bank.say("fedpush", m, g, 3)
        assertEquals(small.lowercase(), small)
        m.gp = 150.0
        val mid = bank.say("fed", m, g, 3)
        assertNotEquals(mid.lowercase(), mid)
        m.gp = 1200.0
        val big = bank.say("fedpush", m, g, 3)
        assertEquals(big.uppercase(), big)
    }

    @Test
    fun theSameSeedGivesTheSameLineAndNeighboursDiffer() {
        val g = game("cindermite")
        val m = g.lead!!
        m.gp = 150.0
        for (seed in 0L..200L) {
            assertEquals(bank.say("fed", m, g, seed), bank.say("fed", m, g, seed))
            assertNotEquals("seed $seed", bank.say("fed", m, g, seed), bank.say("fed", m, g, seed + 1))
        }
        assertTrue((0L..60L).map { bank.say("poke", m, g, it) }.toSet().size >= 4)
    }

    @Test
    fun variablesAreFilledIn() {
        val g = game()
        g.lead!!.gp = 150.0
        g.streak = 12
        for (seed in 0L..80L) {
            val s = bank.say("streak", g.lead, g, seed)
            assertFalse(s, s.contains("{"))
            val l = bank.say("lock", g.lead, g, seed, "YouTube")
            assertTrue(l, l.contains("YouTube"))
        }
    }

    @Test
    fun idleFollowsTheStateOfTheHorde() {
        val g = game()
        g.restDays.clear()
        val t0 = mon + 12 * Rules.H
        Engine.advance(g, t0 + 30 * Rules.H, 0)
        assertEquals("restless", bank.idleSituation(g, t0 + 30 * Rules.H, 0))
        Engine.advance(g, t0 + 50 * Rules.H, 0)
        assertEquals("feral", bank.idleSituation(g, t0 + 50 * Rules.H, 0))
        assertEquals("egg", bank.idleSituation(Game.fresh(mon), mon, 0))
        val calm = game()
        val seen = (0 until 200).map { bank.idleSituation(calm, t0 + it * 20 * 60_000L, 0) }.toSet()
        assertTrue(seen.toString(), "fed" in seen)
        assertTrue(bank.idle(calm, t0, 0).isNotEmpty())
    }
}
