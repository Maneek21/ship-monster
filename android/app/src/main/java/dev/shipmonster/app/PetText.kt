package dev.shipmonster.app

/** Words shown on the widget and the app screens. Pure Kotlin. */
object PetText {
    fun dur(hours: Double): String {
        val h = maxOf(0.0, hours)
        if (h < 1) return "under 1h"
        if (h < 48) return "${h.toInt()}h"
        val d = (h / 24).toInt()
        val r = (h % 24).toInt()
        return if (r > 0) "${d}d ${r}h" else "${d}d"
    }

    /** "just now", "12 min ago", "3h ago", "2d ago" for a span in milliseconds. */
    fun ago(ms: Long): String {
        val min = maxOf(0L, ms) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60}h ago"
            else -> "${min / (24 * 60)}d ago"
        }
    }

    fun formLabel(f: Form): String = when (f) {
        Form.EGG -> "Egg"
        Form.SPROUT -> "Sprout"
        Form.LEAFLING -> "Leafling"
        Form.BLOOM -> "Bloom"
    }

    fun title(s: PetState, now: Long): String = when (PetEngine.status(s, now)) {
        Status.EGG -> "${s.pet.name}, still an egg"
        Status.DEAD -> "R.I.P. ${s.pet.name}"
        else -> "${s.pet.name} the ${formLabel(s.pet.form)}"
    }

    fun pill(s: PetState, now: Long): String = when (PetEngine.status(s, now)) {
        Status.EGG -> "Egg"
        Status.HAPPY -> if (s.pet.ciFailing) "Sick, CI red" else "Happy"
        Status.HUNGRY -> "Hungry"
        Status.MONSTER -> "Monster"
        Status.DEAD -> "Dead"
    }

    fun meta(s: PetState, now: Long): String {
        val p = s.pet
        return when (PetEngine.status(s, now)) {
            Status.EGG -> "Ship something to hatch it."
            Status.DEAD -> if (p.deadCause == Cause.MERCY) "Mercy-unlocked." else "Starved as a monster."
            Status.MONSTER -> "Starving. Dies in ${dur(PetEngine.hoursToNext(s, now) ?: 0.0)}."
            else -> "${p.streak}-day streak, fed ${dur(PetEngine.hoursSinceFed(s, now))} ago"
        }
    }

    /** What happens next and when. */
    fun next(s: PetState, now: Long): String {
        val left = dur(PetEngine.hoursToNext(s, now) ?: 0.0)
        return when (PetEngine.status(s, now)) {
            Status.EGG -> "Ship something to hatch"
            Status.HAPPY -> "Hungry in $left"
            Status.HUNGRY -> "Monster in $left"
            Status.MONSTER -> "Dies in $left"
            Status.DEAD -> "Hatch a new egg to go again"
        }
    }
}
