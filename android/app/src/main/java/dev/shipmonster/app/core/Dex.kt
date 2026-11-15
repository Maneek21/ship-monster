package dev.shipmonster.app.core

enum class Rarity(val label: String, val appetite: Int) {
    COMMON("Common", 5), RARE("Rare", 8), EPIC("Epic", 12), LEGENDARY("Legendary", 16)
}

enum class Flavor(val label: String) { DAWN("Dawn"), DAY("Day"), DUSK("Dusk"), MIDNIGHT("Midnight") }

enum class Voice { NEEDY, GREMLIN, ANXIOUS, DEADPAN, SLEEPY, SMUG, NOBLE, CRYPTIC }

class Species(
    val id: String,
    val name: String,
    val rarity: Rarity,
    val voice: Voice,
    val fav: Flavor,
    val epithet: String,
    val blurb: String,
)

/** The twelve species. Art lives in assets/sprites/<id>.png. */
object Dex {
    val all: List<Species> = listOf(
        Species("mossback", "Mossback", Rarity.COMMON, Voice.NEEDY, Flavor.DAWN, "the Evergreen",
            "A damp little clump that sprouts when it feels loved. It feels loved when you push."),
        Species("cindermite", "Cindermite", Rarity.COMMON, Voice.GREMLIN, Flavor.MIDNIGHT, "the Unquenched",
            "Runs hot. Likes late pushes, force pushes, and anything that smells like risk."),
        Species("puddlewump", "Puddlewump", Rarity.COMMON, Voice.ANXIOUS, Flavor.DAY, "the Deep",
            "Worries about everything, mostly on your behalf. Calms down after lunch."),
        Species("gravelkin", "Gravelkin", Rarity.COMMON, Voice.DEADPAN, Flavor.DUSK, "the Immovable",
            "A rock with opinions. Has seen your commit history and is choosing not to comment."),
        Species("fuzzlet", "Fuzzlet", Rarity.COMMON, Voice.SLEEPY, Flavor.DAWN, "the Well Rested",
            "Mostly fluff and naps. Wakes for breakfast pushes, then goes back down."),
        Species("gloomfang", "Gloomfang", Rarity.RARE, Voice.SMUG, Flavor.MIDNIGHT, "Eater of Deadlines",
            "Thinks it could have written that better. Hunts after midnight."),
        Species("brasshorn", "Brasshorn", Rarity.RARE, Voice.NOBLE, Flavor.DAY, "the Gilded",
            "Carries itself like a knight. Considers every merged pull request a victory in the field."),
        Species("thornback", "Thornback", Rarity.RARE, Voice.GREMLIN, Flavor.DUSK, "the Thousand Spines",
            "One eye, many spikes, zero patience. Grows a new spine for every bad idea you ship."),
        Species("frostnip", "Frostnip", Rarity.RARE, Voice.DEADPAN, Flavor.DAWN, "the Long Winter",
            "Cold, calm and unimpressed. Best fed early, before anyone else is awake."),
        Species("voidling", "Voidling", Rarity.EPIC, Voice.CRYPTIC, Flavor.MIDNIGHT, "the Space Between",
            "A patch of night sky that learned to blink. Nobody knows what it does with the chow."),
        Species("sunmaw", "Sunmaw", Rarity.EPIC, Voice.NOBLE, Flavor.DAY, "the Second Sun",
            "A small star with a big mouth. Shines brighter with every day of your streak."),
        Species("mergewyrm", "Mergewyrm", Rarity.LEGENDARY, Voice.CRYPTIC, Flavor.DUSK, "Born of a Thousand Merges",
            "Said to hatch only for those who close what they open. Coils around clean history."),
    )
    private val byId = all.associateBy { it.id }
    val starters = listOf("mossback", "cindermite", "puddlewump")
    fun get(id: String): Species = byId[id] ?: all[0]
    fun of(r: Rarity): List<Species> = all.filter { it.rarity == r }
}

object Rules {
    const val H = 3_600_000L
    const val D = 24 * H
    const val RESTLESS_H = 24
    const val FERAL_H = 48
    const val FALL_H = 120
    const val WARN_H = 42
    const val TRUCE_H = 12
    const val MERGE_GEMS_PER_DAY = 1
    const val SHIP_DAYS_PER_GEM = 3
    const val MAX_REST_DAYS = 3
    const val MAX_STARS = 5
    const val TREATS_PER_DAY = 3
    const val TREAT_COST = 2
    const val FEAST_COST = 2
    const val STOCK_CHOW = 30
    const val MAX_HIBERNATE_DAYS = 14
    const val HIBERNATE_COOLDOWN_DAYS = 30

    val STAGE_NAMES = arrayOf("Egg", "Speck", "Nibbler", "Biter", "Brute", "Beast", "Behemoth", "Colossus", "Titan")
    val STAGE_GP = intArrayOf(0, 30, 70, 140, 300, 600, 1000, 1800)

    fun stageFor(gp: Double): Int {
        var s = 1
        for (i in STAGE_GP.indices) if (gp >= STAGE_GP[i]) s = i + 1
        return s
    }

    /** 0..1 progress through the current stage. 1 at Titan. */
    fun stageProgress(gp: Double): Float {
        val s = stageFor(gp)
        if (s >= 8) return 1f
        val a = STAGE_GP[s - 1]
        val b = STAGE_GP[s]
        return ((gp - a) / (b - a)).toFloat().coerceIn(0f, 1f)
    }

    val LEVEL_CHOW = intArrayOf(0, 50, 150, 300, 500, 750, 1050, 1400, 1800, 2250)
    val LEVEL_TITLES = arrayOf("Hatchling Keeper", "Stablehand", "Feeder", "Wrangler", "Handler", "Tamer",
        "Warden", "Hordemaster", "Beastlord", "Monarch of Merges")
    private val SLOTS = intArrayOf(1, 2, 2, 3, 3, 4, 4, 5, 5, 5)

    fun levelFor(lifetime: Int): Int {
        var l = 1
        for (i in LEVEL_CHOW.indices) if (lifetime >= LEVEL_CHOW[i]) l = i + 1
        return l
    }
    fun slots(level: Int) = SLOTS[(level - 1).coerceIn(0, 9)]
    fun pantryCap(level: Int) = 60 + 20 * level
    fun title(level: Int) = LEVEL_TITLES[(level - 1).coerceIn(0, 9)]

    val MILESTONES = linkedMapOf(7 to 1, 14 to 1, 30 to 2, 60 to 2, 100 to 3, 180 to 5, 365 to 10)

    fun chowForPush(nthToday: Int) = if (nthToday <= 3) 10 else if (nthToday <= 6) 5 else 2

    fun flavorAt(localHour: Int): Flavor = when (localHour) {
        in 5..10 -> Flavor.DAWN
        in 11..16 -> Flavor.DAY
        in 17..22 -> Flavor.DUSK
        else -> Flavor.MIDNIGHT
    }

    fun localDay(t: Long, tz: Long): Long = Math.floorDiv(t + tz, D)
    fun localHour(t: Long, tz: Long): Int = (Math.floorMod(t + tz, D) / H).toInt()
    /** 0 = Sunday .. 6 = Saturday. */
    fun dow(day: Long): Int = Math.floorMod(day + 4, 7L).toInt()
    fun week(day: Long): Long = Math.floorDiv(day + 3, 7L)   // weeks start on Monday
}
