package dev.shipmonster.app

/*
 * Pet rules. Pure Kotlin, no Android imports, so it runs in plain JVM unit tests.
 * Mirrors simulator/engine.js. Time is epoch milliseconds passed in by the caller.
 */

enum class Status { EGG, HAPPY, HUNGRY, MONSTER, DEAD }
enum class Form { EGG, SPROUT, LEAFLING, BLOOM }
enum class Cause { STARVED, MERCY }

data class Grave(
    val name: String,
    val form: Form,
    val cause: Cause,
    val diedAt: Long,
    val days: Int,
    val bestStreak: Int,
    val ships: Int
)

data class Pet(
    val name: String,
    val bornAt: Long,
    var hatchedAt: Long? = null,
    var lastFedAt: Long? = null,
    var streak: Int = 0,
    var bestStreak: Int = 0,
    var lastShipDay: Long? = null,
    var form: Form = Form.EGG,
    var commits: Int = 0,
    var merges: Int = 0,
    var ciFailing: Boolean = false,
    var deadAt: Long? = null,
    var deadCause: Cause? = null
)

class PetState(
    var pet: Pet,
    var petCount: Int = 1,
    val graveyard: MutableList<Grave> = mutableListOf()
)

data class ShipResult(val was: Status, val hatched: Boolean, val evolved: Form?)

object PetEngine {
    const val H = 3_600_000L
    const val D = 24 * H
    const val HUNGRY_AT = 24
    const val MONSTER_AT = 48
    const val MONSTER_HOURS = 72
    const val DEAD_AT = MONSTER_AT + MONSTER_HOURS
    const val LEAFLING_STREAK = 7
    const val BLOOM_STREAK = 30

    val NAMES = listOf("Pip", "Mochi", "Byte", "Nugget", "Dot", "Fig", "Zip", "Pebble")

    fun newPet(now: Long, n: Int): Pet = Pet(name = NAMES[n % NAMES.size], bornAt = now)

    fun newState(now: Long): PetState = PetState(newPet(now, 0))

    fun hoursSinceFed(s: PetState, now: Long): Double {
        val fed = s.pet.lastFedAt ?: return 0.0
        val end = s.pet.deadAt ?: now
        return (end - fed).toDouble() / H
    }

    fun status(s: PetState, now: Long): Status {
        val p = s.pet
        if (p.deadAt != null) return Status.DEAD
        if (p.hatchedAt == null) return Status.EGG
        val h = hoursSinceFed(s, now)
        return when {
            h >= DEAD_AT -> Status.DEAD
            h >= MONSTER_AT -> Status.MONSTER
            h >= HUNGRY_AT -> Status.HUNGRY
            else -> Status.HAPPY
        }
    }

    /** 1.0 right after a ship, 0.0 once the monster threshold is reached. */
    fun fullness(s: PetState, now: Long): Double {
        return when (status(s, now)) {
            Status.EGG -> 1.0
            Status.DEAD -> 0.0
            else -> (1.0 - hoursSinceFed(s, now) / MONSTER_AT).coerceIn(0.0, 1.0)
        }
    }

    /** Hours until the next state change, or null for an egg or a dead pet. */
    fun hoursToNext(s: PetState, now: Long): Double? {
        val h = hoursSinceFed(s, now)
        return when (status(s, now)) {
            Status.HAPPY -> HUNGRY_AT - h
            Status.HUNGRY -> MONSTER_AT - h
            Status.MONSTER -> DEAD_AT - h
            else -> null
        }
    }

    private fun formFor(p: Pet): Form {
        val target = when {
            p.streak >= BLOOM_STREAK -> Form.BLOOM
            p.streak >= LEAFLING_STREAK -> Form.LEAFLING
            else -> Form.SPROUT
        }
        return if (target.ordinal > p.form.ordinal) target else p.form
    }

    private fun die(s: PetState, cause: Cause, at: Long) {
        val p = s.pet
        p.deadAt = at
        p.deadCause = cause
        val start = p.hatchedAt ?: p.bornAt
        val days = maxOf(0L, (at - start) / D).toInt()
        s.graveyard.add(0, Grave(p.name, p.form, cause, at, days, p.bestStreak, p.commits + p.merges))
    }

    /** Records a starvation death if it is due. Call before reading or changing state. Returns true if the pet just died. */
    fun tick(s: PetState, now: Long): Boolean {
        val p = s.pet
        val fed = p.lastFedAt
        if (p.deadAt != null || p.hatchedAt == null || fed == null) return false
        if (hoursSinceFed(s, now) < DEAD_AT) return false
        die(s, Cause.STARVED, fed + DEAD_AT * H)
        return true
    }

    /** A commit or merged PR. Returns null if the pet is dead. tzOffsetMs makes streaks follow the local calendar day. */
    fun ship(s: PetState, now: Long, merge: Boolean, tzOffsetMs: Long = 0L): ShipResult? {
        tick(s, now)
        val p = s.pet
        if (p.deadAt != null) return null
        val was = status(s, now)
        val hatched = p.hatchedAt == null
        if (hatched) p.hatchedAt = now
        val day = Math.floorDiv(now + tzOffsetMs, D)
        val last = p.lastShipDay
        if (last == null || day > last + 1) {
            p.streak = 1
        } else if (day == last + 1) {
            p.streak += 1
        }
        p.lastShipDay = day
        if (p.streak > p.bestStreak) p.bestStreak = p.streak
        p.lastFedAt = now
        if (merge) p.merges += 1 else p.commits += 1
        val before = p.form
        p.form = formFor(p)
        val evolved = if (before != p.form && !hatched) p.form else null
        return ShipResult(was, hatched, evolved)
    }

    /** Only a monster can be mercy-unlocked. Kills the pet. */
    fun mercy(s: PetState, now: Long): Boolean {
        tick(s, now)
        if (status(s, now) != Status.MONSTER) return false
        die(s, Cause.MERCY, now)
        return true
    }

    fun hatchNew(s: PetState, now: Long): Boolean {
        tick(s, now)
        if (s.pet.deadAt == null) return false
        s.pet = newPet(now, s.petCount)
        s.petCount += 1
        return true
    }

    fun epitaph(g: Grave): String {
        val lived = when {
            g.days < 1 -> "Lived less than a day."
            g.days == 1 -> "Lived 1 day."
            else -> "Lived ${g.days} days."
        }
        val how = if (g.cause == Cause.MERCY) {
            "Mercy-unlocked so its owner could scroll."
        } else {
            "Starved after $MONSTER_HOURS hours as a monster."
        }
        return "R.I.P. ${g.name}. $lived $how"
    }
}
