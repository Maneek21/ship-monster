package dev.shipmonster.app

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.shipmonster.app.core.Dex
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Flavor
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Hatch
import dev.shipmonster.app.core.LogEntry
import dev.shipmonster.app.core.Monster
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Rules
import dev.shipmonster.app.core.Species

/** The whole app: onboarding, then four tabs (Den, Horde, Nest, Log). Setup is its own screen. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var den: DenView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    private var tab = 0
    private var detailId = -1
    private var focusId = -1
    private var step = 0
    private var picked: String? = null
    private var typedUser = ""
    private var said: String? = null
    private var saidUntil = 0L
    private var syncing = false
    private var reveal: Hatch? = null
    private var news: List<LogEntry> = emptyList()
    private val scrolls = IntArray(4)
    private var scroller: ScrollView? = null

    private val FLAVOR_ICON = arrayOf("dawn", "day", "dusk", "midnight")
    private val FLAVOR_HOURS = arrayOf("5am to 11am", "11am to 5pm", "5pm to 11pm", "11pm to 5am")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.col(this).apply {
            setBackgroundColor(getColor(R.color.den))
            fitsSystemWindows = true
        }
        content = FrameLayout(this)
        nav = Ui.row(this).apply { setBackgroundColor(getColor(R.color.den_soft)) }
        root.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        root.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP))
        setContentView(root)
        den = DenView(this)
        den.onPoke = { id -> poke(id) }
        if (savedInstanceState != null) tab = savedInstanceState.getInt("tab", 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        PetRuntime.refresh(this)
        val seen = Prefs.newsSeen(this)
        if (seen > 0 && Prefs.onboarded(this)) {
            val fresh = GameStore.load(this).log.filter { it.at > seen }
            if (fresh.isNotEmpty()) news = (fresh + news).distinctBy { it.at.toString() + it.text }.take(30)
        }
        SyncJobService.schedule(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (Prefs.onboarded(this) && Prefs.username(this).isNotEmpty() &&
            System.currentTimeMillis() - Prefs.lastSyncAt(this) > 90_000) sync(false)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hush)
        Prefs.setNewsSeen(this, GameStore.now(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            Prefs.onboarded(this) && tab == 1 && detailId >= 0 -> { detailId = -1; render() }
            !Prefs.onboarded(this) && step > 0 -> { step--; render() }
            Prefs.onboarded(this) && tab != 0 -> { tab = 0; render() }
            else -> super.onBackPressed()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun say(text: String) {
        said = text
        saidUntil = System.currentTimeMillis() + 6000
        handler.removeCallbacks(hush)
        handler.postDelayed(hush, 6100)
    }

    private val hush = Runnable { if (tab == 0) render() }

    private fun buzz(strong: Boolean = false) {
        content.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun line(g: Game, situation: String, m: Monster?): String =
        GameStore.lines(this).say(situation, m, g, System.currentTimeMillis() / 700)

    // ------------------------------------------------------------------ actions

    private fun sync(tell: Boolean) {
        if (syncing) return
        syncing = true
        if (tell) render()
        val app = applicationContext
        Thread {
            val out = try {
                GitHubSync.run(app)
            } catch (e: Exception) {
                SyncOutcome("Could not reach GitHub.", 0, false)
            }
            handler.post {
                syncing = false
                if (out.fed > 0) {
                    val g = GameStore.load(this)
                    say(line(g, "fedpush", g.lead))
                    den.joy()
                }
                PetRuntime.refresh(this)
                render()
                if (tell || out.fed > 0) toast(out.message)
            }
        }.start()
    }

    private fun poke(id: Int) {
        val g = GameStore.load(this)
        val m = g.monster(id) ?: return
        focusId = id
        buzz()
        say(line(g, "poke", m))
        render()
    }

    private fun treat(id: Int) {
        var m: Monster? = null
        val code = GameStore.edit(this) { g, now -> m = g.monster(id); Engine.treat(g, id, now) }
        val g = GameStore.load(this)
        when (code) {
            2 -> { say(line(g, "treatfav", m)); den.joy(id) }
            1 -> { say(line(g, "treat", m)); den.joy(id) }
            -2 -> say(line(g, "treatnone", m))
            -3 -> toast("Not enough chow. A treat costs ${Rules.TREAT_COST}.")
            else -> {}
        }
        PetRuntime.refresh(this)
        render()
    }

    private fun feast() {
        val ok = GameStore.edit(this) { g, now -> Engine.feast(g, now) != null }
        if (ok) {
            val g = GameStore.load(this)
            say(line(g, "feast", g.lead))
            den.joy()
            PetRuntime.refresh(this)
        } else {
            toast("A feast costs ${Rules.FEAST_COST} gems. Merged pull requests and streaks pay gems.")
        }
        render()
    }

    private fun addWidget() {
        Prefs.setHintDone(this, "widget")
        val awm = getSystemService(AppWidgetManager::class.java)
        if (awm != null && awm.isRequestPinAppWidgetSupported) {
            awm.requestPinAppWidget(ComponentName(this, PetWidget::class.java), null, null)
        } else {
            toast("Long press your home screen, then Widgets, then Ship Monster.")
        }
        render()
    }

    private fun hasWidget(): Boolean {
        val awm = getSystemService(AppWidgetManager::class.java) ?: return false
        return awm.getAppWidgetIds(ComponentName(this, PetWidget::class.java)).isNotEmpty()
    }

    // ------------------------------------------------------------------ frame

    private fun render() {
        val g = GameStore.load(this)
        val now = GameStore.now(this)
        scroller?.let { if (tab in 0..3) scrolls[tab] = it.scrollY }
        content.removeAllViews()
        (den.parent as? LinearLayout)?.removeView(den)
        if (!Prefs.onboarded(this)) {
            nav.visibility = View.GONE
            scroller = null
            content.addView(scroll(onboarding(g), -1))
            return
        }
        nav.visibility = View.VISIBLE
        paintNav()
        val page = when (tab) {
            0 -> denPage(g, now)
            1 -> g.monster(detailId)?.let { detailPage(g, it, now) } ?: hordePage(g)
            2 -> nestPage(g)
            else -> logPage(g, now)
        }
        content.addView(scroll(page, tab))
    }

    private fun scroll(page: View, index: Int): ScrollView {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val pad = Ui.dp(this, 16)
        page.setPadding(pad, pad, pad, pad)
        sv.addView(page)
        if (index >= 0) {
            scroller = sv
            val y = scrolls[index]
            if (y > 0) sv.post { sv.scrollTo(0, y) }
        }
        return sv
    }

    private fun paintNav() {
        nav.removeAllViews()
        val names = arrayOf("Den", "Horde", "Nest", "Log", "Setup")
        for ((i, name) in names.withIndex()) {
            val on = i == tab
            val t = Ui.text(this, name, 13f, if (on) R.color.gold else R.color.on_den_soft, bold = true, mono = true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
            t.contentDescription = "tab $name"
            t.setOnClickListener {
                if (i == 4) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    if (i == 1 && tab == 1) detailId = -1
                    tab = i
                    render()
                }
            }
            nav.addView(t, Ui.lp(0, Ui.WRAP, 1f))
        }
    }

    private fun chip(icon: String, label: String): View {
        val r = Ui.row(this)
        r.setBackgroundResource(R.drawable.chip)
        r.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5))
        r.addView(Ui.sprite(this, Art.icon(this, icon), 18))
        r.addView(Ui.text(this, label, 13f, R.color.on_den, bold = true, mono = true), Ui.lp(Ui.WRAP, Ui.WRAP, start = Ui.dp(this, 4)))
        r.layoutParams = Ui.lp(Ui.WRAP, Ui.WRAP, start = Ui.dp(this, 6))
        return r
    }

    private fun header(g: Game, title: String): View {
        val r = Ui.row(this)
        val left = Ui.col(this)
        left.addView(Ui.text(this, title, 18f, R.color.on_den, bold = true, mono = true))
        left.addView(Ui.text(this, "Lv ${g.level} ${Rules.title(g.level)}", 12f, R.color.on_den_soft))
        r.addView(left, Ui.lp(0, Ui.WRAP, 1f))
        r.addView(chip("flame", "${g.streak}").apply { contentDescription = "streak ${g.streak}" })
        r.addView(chip("chow", "${g.pantry}").apply { contentDescription = "chow ${g.pantry}" })
        r.addView(chip("gem", "${g.gems}").apply { contentDescription = "gems ${g.gems}" })
        return r
    }

    private fun stars(n: Int): String = if (n <= 0) "" else "★".repeat(n)

    // ------------------------------------------------------------------ onboarding

    private fun onboarding(g: Game): View {
        val page = Ui.col(this)
        page.gravity = Gravity.CENTER
        page.addView(Ui.text(this, "SHIP MONSTER", 22f, R.color.on_den, bold = true, mono = true))
        page.addView(Ui.text(this, "Step ${step + 1} of 5", 12f, R.color.on_den_soft), Ui.lp(Ui.WRAP, Ui.WRAP, top = Ui.dp(this, 2)))
        val card = Ui.card(this)
        card.gravity = Gravity.CENTER_HORIZONTAL
        page.addView(card)
        when (step) {
            0 -> {
                card.addView(Ui.sprite(this, Art.portrait(this, "mossback", 5, Art.HAPPY, grow = false), 150))
                card.addView(Ui.h(this, "Your monsters eat shipped code").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
                card.addView(Ui.body(this, "Every push to GitHub is chow for the pantry. Every merged pull request is a gem. You do not log anything. Just ship.").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
            }
            1 -> {
                val r = Ui.row(this)
                r.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                for ((s, size) in listOf(1 to 30, 3 to 52, 5 to 76, 8 to 108)) r.addView(Ui.sprite(this, Art.portrait(this, "cindermite", s, Art.IDLE, grow = false), size))
                card.addView(r)
                card.addView(Ui.h(this, "Feed them and they grow").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
                card.addView(Ui.body(this, "Eight sizes, from Speck to Titan. Twelve species to hatch, each with its own voice and favourite flavour. Gems buy eggs and feasts.").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
            }
            2 -> {
                card.addView(Ui.sprite(this, Art.portrait(this, "gloomfang", 5, Art.FERAL, grow = false), 150))
                card.addView(Ui.h(this, "Stop shipping and they go feral").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
                card.addView(Ui.body(this, "After 48 hours with no ship the horde goes feral. Your streak resets, everyone shrinks a size, and the horde can lock the apps you choose until you push. 72 hours later it falls.\n\nSaturday and Sunday are rest days. You can change that in Setup.").apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
            }
            3 -> {
                card.gravity = Gravity.START
                card.addView(Ui.h(this, "Where do you ship?"))
                card.addView(Ui.body(this, "Your GitHub username is enough. Ship Monster reads your public activity. No password, nothing to install on GitHub. Private repos need a token, which you can add later in Setup."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
                val et = EditText(this, null, 0, R.style.Field)
                et.hint = "GitHub username"
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                et.filters = arrayOf(InputFilter.LengthFilter(39))
                et.setText(if (typedUser.isNotEmpty()) typedUser else Prefs.username(this))
                et.contentDescription = "username"
                card.addView(et, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 12)))
                page.addView(Ui.button(this, "Continue") {
                    val name = et.text.toString().trim().removePrefix("@")
                    typedUser = name
                    if (!SyncLogic.validUsername(name)) {
                        toast("That does not look like a GitHub username.")
                    } else {
                        Prefs.setUsername(this, name)
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)
                        step = 4
                        render()
                    }
                })
            }
            else -> {
                card.gravity = Gravity.START
                card.addView(Ui.h(this, "Pick your first egg"))
                card.addView(Ui.body(this, "It hatches on your first push. If you shipped in the last two days, it hatches right now with your real streak."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
                starterTiles(card)
                page.addView(Ui.button(this, if (picked == null) "Pick one above" else "Start with ${Dex.get(picked!!).name}") {
                    val p = picked
                    if (p == null) {
                        toast("Tap one of the three.")
                    } else {
                        GameStore.edit(this) { game, _ -> Engine.pickStarter(game, p) }
                        Prefs.setOnboarded(this, true)
                        Prefs.setNewsSeen(this, GameStore.now(this))
                        tab = 0
                        render()
                        sync(true)
                    }
                })
            }
        }
        if (step < 3) page.addView(Ui.button(this, "Next") { step++; render() })
        if (step > 0) page.addView(Ui.button(this, "Back", Ui.OUTLINE) { step--; render() })
        return page
    }

    private fun starterTiles(into: LinearLayout) {
        for (id in Dex.starters) {
            val sp = Dex.get(id)
            val on = picked == id
            val tile = Ui.row(this)
            tile.setBackgroundResource(if (on) R.drawable.tile_on else R.drawable.tile)
            tile.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
            tile.contentDescription = "starter ${sp.name}" + (if (on) " selected" else "")
            tile.addView(Ui.sprite(this, Art.portrait(this, id, 3, if (on) Art.HAPPY else Art.IDLE, grow = false), 64))
            val c = Ui.col(this)
            c.addView(Ui.text(this, sp.name, 15f, bold = true, mono = true))
            c.addView(Ui.text(this, "${voiceWord(sp)}. Loves ${sp.fav.label} chow.", 13f, R.color.ink_soft))
            tile.addView(c, Ui.lp(0, Ui.WRAP, 1f, start = Ui.dp(this, 10)))
            tile.setOnClickListener { picked = id; render() }
            into.addView(tile, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
        }
    }

    private fun voiceWord(sp: Species): String = sp.voice.name.lowercase().replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------ den

    private fun denPage(g: Game, now: Long): View {
        val tz = GameStore.tz(now)
        val page = Ui.col(this)
        page.addView(header(g, "SHIP MONSTER"))
        val mood = Engine.mood(g)

        den.set(g, now, tz)
        page.addView(den, Ui.lp(Ui.MATCH, Ui.dp(this, 250), top = Ui.dp(this, 12)))

        val lead = g.lead
        val bubbleText = if (said != null && System.currentTimeMillis() < saidUntil) said!! else GameStore.lines(this).idle(g, now, tz)
        val bubble = Ui.text(this, bubbleText, 15f, R.color.ink)
        bubble.setBackgroundResource(R.drawable.bubble)
        bubble.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        bubble.contentDescription = "speech"
        page.addView(bubble, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))

        if (news.isNotEmpty()) page.addView(newsCard())

        if (g.needsStarter) {
            val card = Ui.card(this)
            card.addView(Ui.h(this, if (g.falls > 0) "The horde fell. Start again." else "Pick an egg"))
            card.addView(Ui.body(this, "It hatches on your next push." + (if (g.statues().isNotEmpty()) " Your statues are waiting in the Horde tab." else "")), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            starterTiles(card)
            card.addView(Ui.button(this, if (picked == null) "Pick one above" else "Start with ${Dex.get(picked!!).name}") {
                val p = picked
                if (p != null) {
                    GameStore.edit(this) { game, _ -> Engine.pickStarter(game, p) }
                    picked = null
                    render()
                }
            })
            page.addView(card)
        } else if (lead == null) {
            val card = Ui.card(this)
            card.addView(Ui.h(this, "Waiting for your first push"))
            card.addView(Ui.body(this, "Push a commit or merge a pull request on GitHub as ${Prefs.username(this)}. The egg hatches the moment Ship Monster sees it."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            page.addView(card)
        } else {
            // the horde clock
            val clock = Ui.card(this)
            val top = Ui.row(this)
            val label = when (mood) { Mood.FERAL -> "FERAL"; Mood.RESTLESS -> "RESTLESS"; else -> "CALM" }
            val state = Ui.text(this, label, 16f, PetWidget.stateColor(mood), bold = true, mono = true)
            state.contentDescription = "state $label"
            top.addView(state, Ui.lp(0, Ui.WRAP, 1f))
            val rest = Engine.isRest(g, now, tz)
            val next = when {
                Engine.hibernating(g, now) -> "Hibernating"
                rest -> "Rest day. Clock paused"
                mood == Mood.FERAL -> "Falls in ${Fmt.hours(Engine.hoursToNext(g))}"
                mood == Mood.RESTLESS -> "Feral in ${Fmt.hours(Engine.hoursToNext(g))}"
                else -> "Restless in ${Fmt.hours(Engine.hoursToNext(g))}"
            }
            top.addView(Ui.text(this, next, 13f, R.color.ink_soft, mono = true))
            clock.addView(top)
            clock.addView(Ui.bar(this, Engine.danger(g), PetWidget.stateColor(mood), floatArrayOf(0.2f, 0.4f)))
            val last = if (g.lastShipAt > 0) "Last ship ${Fmt.ago(now - g.lastShipAt)}." else "No ship yet."
            val extra = when {
                mood == Mood.FERAL && now < g.truceUntil -> " Truce: apps open for ${Fmt.hours(((g.truceUntil - now) / Rules.H).toInt() + 1)}."
                mood == Mood.FERAL && Prefs.lockEnabled(this) -> " Your chosen apps are locked. Ship to lift it."
                mood == Mood.FERAL -> " Ship to calm the horde."
                else -> ""
            }
            clock.addView(Ui.text(this, last + extra, 12f, R.color.ink_soft), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
            page.addView(clock)

            // the monster in focus
            val party = g.partyMonsters()
            val m = party.firstOrNull { it.id == focusId } ?: lead
            val card = Ui.card(this)
            if (party.size > 1) {
                val strip = Ui.row(this)
                for (p in party) {
                    val t = Ui.col(this)
                    t.gravity = Gravity.CENTER
                    t.setBackgroundResource(if (p.id == m.id) R.drawable.tile_on else R.drawable.tile)
                    t.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4))
                    t.addView(Ui.sprite(this, Art.portrait(this, p.species, p.stage, Art.variantFor(g, p, false)), 40))
                    t.contentDescription = "party ${p.name}"
                    t.setOnClickListener { focusId = p.id; render() }
                    strip.addView(t, Ui.lp(0, Ui.WRAP, 1f, start = if (strip.childCount > 0) Ui.dp(this, 6) else 0))
                }
                card.addView(strip, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(this@MainActivity, 10) })
            }
            monsterBars(card, g, m)
            val buttons = Ui.row(this)
            val left = Rules.TREATS_PER_DAY - m.treats
            buttons.addView(Ui.button(this, "Treat ($left left)", Ui.PRIMARY) { treat(m.id) }.apply { contentDescription = "treat" }, Ui.lp(0, Ui.WRAP, 1f))
            if (m.id != lead.id) {
                buttons.addView(Ui.button(this, "Make lead", Ui.LIGHT) {
                    GameStore.edit(this) { game, _ -> Engine.makeLead(game, m.id) }
                    PetRuntime.refresh(this)
                    render()
                }, Ui.lp(0, Ui.WRAP, 1f, start = Ui.dp(this, 8)))
            }
            card.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
            page.addView(card)
        }

        // pantry
        val pantry = Ui.card(this)
        val head = Ui.row(this)
        head.addView(Ui.h(this, "Pantry"), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, "${g.pantry} of ${g.pantryCap} chow", 13f, R.color.ink_soft, mono = true))
        pantry.addView(head)
        val flavors = Ui.row(this)
        for (f in Flavor.values()) {
            val cell = Ui.row(this)
            cell.gravity = Gravity.CENTER
            cell.addView(Ui.sprite(this, Art.icon(this, FLAVOR_ICON[f.ordinal]), 26))
            cell.addView(Ui.text(this, "${g.chow[f.ordinal]}", 15f, bold = true, mono = true), Ui.lp(Ui.WRAP, Ui.WRAP, start = Ui.dp(this, 4)))
            cell.contentDescription = "${f.label} chow ${g.chow[f.ordinal]}"
            flavors.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
        }
        pantry.addView(flavors, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        val nowFlavor = Rules.flavorAt(Rules.localHour(now, tz))
        pantry.addView(Ui.text(this, "A push right now makes ${nowFlavor.label} chow. Monsters graze on their own.", 12f, R.color.ink_soft), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        val row = Ui.row(this)
        row.addView(Ui.button(this, if (syncing) "Checking..." else "Sync now", Ui.LIGHT) { sync(true) }, Ui.lp(0, Ui.WRAP, 1f))
        if (lead != null) row.addView(Ui.button(this, "Feast (${Rules.FEAST_COST} gems)", Ui.LIGHT) { feast() }, Ui.lp(0, Ui.WRAP, 1f, start = Ui.dp(this, 8)))
        pantry.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
        val msg = Prefs.lastSyncMessage(this)
        if (msg.isNotEmpty()) {
            val t = Ui.text(this, "$msg (${Fmt.ago(System.currentTimeMillis() - Prefs.lastSyncAt(this))})", 12f, if (Prefs.lastSyncOk(this)) R.color.ink_soft else R.color.bad)
            t.contentDescription = "sync status"
            pantry.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        }
        page.addView(pantry)

        // nudges
        if (!hasWidget() && !Prefs.hintDone(this, "widget")) {
            val c = Ui.card(this)
            c.addView(Ui.h(this, "Put the horde on your home screen"))
            c.addView(Ui.body(this, "The widget shows your lead monster, what it has to say, and how long until things get ugly."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            c.addView(Ui.button(this, "Add the widget") { addWidget() })
            c.addView(Ui.button(this, "Not now", Ui.LIGHT) { Prefs.setHintDone(this, "widget"); render() })
            page.addView(c)
        }
        if (!(Prefs.lockEnabled(this) && LockAccess.ready(this)) && !Prefs.hintDone(this, "lock")) {
            val c = Ui.card(this)
            c.addView(Ui.h(this, "Give the horde some teeth"))
            c.addView(Ui.body(this, "Optional. Pick the apps that distract you. When the horde goes feral they lock until you ship."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            c.addView(Ui.button(this, "Set up the app lock") { startActivity(Intent(this, SettingsActivity::class.java)) })
            c.addView(Ui.button(this, "No lock for me", Ui.LIGHT) { Prefs.setHintDone(this, "lock"); render() })
            page.addView(c)
        }
        return page
    }

    /** What changed since the app was last open: ships rolled up into one line, everything else listed. */
    private fun newsCard(): View {
        val c = Ui.card(this)
        c.setBackgroundResource(R.drawable.tile_on)
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14))
        c.addView(Ui.h(this, "While you were away").apply { contentDescription = "news" })
        val pushes = news.count { it.kind == "push" }
        val merges = news.count { it.kind == "merge" }
        if (pushes + merges > 0) {
            val parts = ArrayList<String>()
            if (pushes > 0) parts.add(if (pushes == 1) "1 push" else "$pushes pushes")
            if (merges > 0) parts.add(if (merges == 1) "1 merged pull request" else "$merges merged pull requests")
            c.addView(Ui.text(this, parts.joinToString(" and ") + " fed the horde.", 13f), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        }
        for (e in news.filter { it.kind != "push" && it.kind != "merge" }.take(6)) {
            c.addView(Ui.text(this, e.text, 13f), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        }
        c.addView(Ui.button(this, "Got it", Ui.LIGHT) { news = emptyList(); render() })
        return c
    }

    /** Name, belly, growth and favourite flavour of one monster. */
    private fun monsterBars(card: LinearLayout, g: Game, m: Monster) {
        val head = Ui.row(this)
        val name = Ui.h(this, m.title)
        name.contentDescription = "monster ${m.name} stage ${m.stage}"
        head.addView(name, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, (stars(m.stars) + " " + m.sp.rarity.label).trim(), 12f, R.color.ink_soft, mono = true))
        card.addView(head)
        if (m.stone) {
            card.addView(Ui.body(this, "Turned to stone when the horde fell. A gem or a feast wakes it."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
            return
        }
        val inParty = m.id in g.party
        val belly = Ui.row(this)
        belly.addView(Ui.text(this, "Belly", 12f, R.color.ink_soft, mono = true), Ui.lp(Ui.dp(this, 62), Ui.WRAP, top = Ui.dp(this, 6)))
        belly.addView(Ui.bar(this, (m.belly / 100).toFloat(), if (m.belly < 30) R.color.bad else R.color.ok), Ui.lp(0, Ui.dp(this, 14), 1f, top = Ui.dp(this, 6)))
        card.addView(belly)
        val grow = Ui.row(this)
        grow.addView(Ui.text(this, "Growth", 12f, R.color.ink_soft, mono = true), Ui.lp(Ui.dp(this, 62), Ui.WRAP, top = Ui.dp(this, 6)))
        grow.addView(Ui.bar(this, Rules.stageProgress(m.gp), R.color.monster), Ui.lp(0, Ui.dp(this, 14), 1f, top = Ui.dp(this, 6)))
        card.addView(grow)
        val s = m.stage
        val nextText = if (s >= 8) "Fully grown. There is nothing bigger than a Titan."
        else "${Rules.STAGE_NAMES[s + 1]} in ${(Rules.STAGE_GP[s] - m.gp).toInt().coerceAtLeast(1)} growth. A well fed day is worth 10."
        card.addView(Ui.text(this, if (inParty) nextText else "Resting in the den. It does not eat, grow or fall.", 12f, R.color.ink_soft), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        val fav = Ui.row(this)
        fav.addView(Ui.sprite(this, Art.icon(this, FLAVOR_ICON[m.sp.fav.ordinal]), 20))
        fav.addView(Ui.text(this, "Loves ${m.sp.fav.label} chow (pushed ${FLAVOR_HOURS[m.sp.fav.ordinal]})", 12f, R.color.ink_soft), Ui.lp(Ui.WRAP, Ui.WRAP, start = Ui.dp(this, 6)))
        card.addView(fav, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
    }

    // ------------------------------------------------------------------ horde

    private fun grid(items: List<View>, columns: Int): LinearLayout {
        val out = Ui.col(this)
        var r: LinearLayout? = null
        for ((i, v) in items.withIndex()) {
            if (i % columns == 0) {
                r = Ui.row(this)
                r.gravity = Gravity.TOP
                out.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
            }
            r!!.addView(v, Ui.lp(0, Ui.WRAP, 1f, start = if (i % columns > 0) Ui.dp(this, 8) else 0))
        }
        val missing = (columns - items.size % columns) % columns
        repeat(missing) { r?.addView(View(this), Ui.lp(0, 1, 1f, start = Ui.dp(this, 8))) }
        return out
    }

    private fun tile(spriteView: View, title: String, sub: String, badge: String, on: Boolean): LinearLayout {
        val t = Ui.col(this)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setBackgroundResource(if (on) R.drawable.tile_on else R.drawable.tile)
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8))
        t.addView(spriteView)
        t.addView(Ui.text(this, title, 13f, bold = true, mono = true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        t.addView(Ui.text(this, sub, 11f, R.color.ink_soft).apply { gravity = Gravity.CENTER; maxLines = 1 })
        if (badge.isNotEmpty()) t.addView(Ui.text(this, badge, 10f, R.color.monster, bold = true, mono = true).apply { gravity = Gravity.CENTER })
        return t
    }

    private fun hordePage(g: Game): View {
        val page = Ui.col(this)
        page.addView(header(g, "HORDE"))
        val card = Ui.card(this)
        card.addView(Ui.h(this, "Your monsters (${g.monsters.size})"))
        card.addView(Ui.body(this, "Party ${g.party.size} of ${g.slots}. " + nextSlotText(g)), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        if (g.monsters.isEmpty()) {
            card.addView(Ui.body(this, "Nobody here yet. Your first egg hatches when you push."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        } else {
            val ordered = g.partyMonsters() + g.den() + g.statues()
            val tiles = ordered.map { m ->
                val badge = when { m.stone -> "STONE"; g.party.firstOrNull() == m.id -> "LEAD"; m.id in g.party -> "PARTY"; else -> "DEN" }
                tile(Ui.sprite(this, Art.portrait(this, m.species, m.stage, Art.variantFor(g, m, false)), 72), m.name,
                    (m.stageName + " " + stars(m.stars)).trim(), badge, m.id in g.party).apply {
                    contentDescription = "open ${m.name}"
                    setOnClickListener { detailId = m.id; scrolls[1] = 0; render() }
                }
            }
            card.addView(grid(tiles, 3))
        }
        page.addView(card)

        val dex = Ui.card(this)
        dex.addView(Ui.h(this, "Monsterdex ${g.seen.size} of ${Dex.all.size}"))
        dex.addView(Ui.body(this, "Eggs from the Nest fill this in."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        val tiles = Dex.all.map { sp ->
            val seen = sp.id in g.seen
            val frame = Art.portrait(this, sp.id, 5, Art.IDLE, grow = false)
            val img = Ui.sprite(this, if (seen) frame else Art.silhouette(frame, getColor(R.color.ink)), 64)
            tile(img, if (seen) sp.name else "???", sp.rarity.label, "", false).apply {
                contentDescription = "dex " + (if (seen) sp.name else "unknown ${sp.rarity.label}")
                setOnClickListener {
                    toast(if (seen) "${sp.name}. ${sp.blurb}" else "Not hatched yet. ${sp.rarity.label} eggs can hold it.")
                }
            }
        }
        dex.addView(grid(tiles, 3))
        page.addView(dex)
        return page
    }

    private fun nextSlotText(g: Game): String {
        for (l in g.level + 1..10) if (Rules.slots(l) > g.slots) return "Another slot opens at keeper level $l."
        return "That is the largest party there is."
    }

    private fun detailPage(g: Game, m: Monster, now: Long): View {
        val page = Ui.col(this)
        page.addView(Ui.button(this, "Back to the horde", Ui.OUTLINE) { detailId = -1; render() }.apply { layoutParams = Ui.lp(Ui.WRAP, Ui.WRAP) })
        val card = Ui.card(this)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(Ui.sprite(this, Art.portrait(this, m.species, m.stage, Art.variantFor(g, m, false)), 170))
        val inner = Ui.col(this)
        monsterBars(inner, g, m)
        card.addView(inner, Ui.lp(Ui.MATCH, Ui.WRAP))
        page.addView(card)

        val about = Ui.card(this)
        about.addView(Ui.h(this, "${m.sp.name}, ${m.sp.rarity.label.lowercase()}"))
        about.addView(Ui.body(this, m.sp.blurb), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        val days = ((now - m.bornAt) / Rules.D).coerceAtLeast(0)
        about.addView(Ui.text(this, "Voice: ${voiceWord(m.sp)}\nEats ${m.sp.rarity.appetite} chow a day\nHatched $days days ago\nBiggest so far: ${Rules.STAGE_NAMES[maxOf(m.peak, m.stage)]}" +
            (if (m.stars > 0) "\n${m.stars} of ${Rules.MAX_STARS} stars: grows ${m.stars * 10}% faster" else ""), 13f, R.color.ink, mono = true), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        page.addView(about)

        val evo = Ui.card(this)
        evo.addView(Ui.h(this, "Evolution"))
        val strip = Ui.row(this)
        strip.gravity = Gravity.BOTTOM
        val reached = maxOf(m.peak, m.stage)
        for (s in 1..8) {
            val f = Art.portrait(this, m.species, s, Art.IDLE)
            val cell = Ui.col(this)
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.addView(Ui.sprite(this, if (s <= reached) f else Art.silhouette(f, getColor(R.color.ink)), 36))
            cell.addView(Ui.text(this, "$s", 10f, if (s == m.stage) R.color.monster else R.color.ink_soft, bold = s == m.stage, mono = true).apply { gravity = Gravity.CENTER })
            strip.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
        }
        evo.addView(strip, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        evo.addView(Ui.text(this, Rules.STAGE_NAMES.drop(1).joinToString(", "), 11f, R.color.ink_soft), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        page.addView(evo)

        val act = Ui.card(this)
        fun change(block: (Game, Long) -> Boolean, fail: String) {
            if (!GameStore.edit(this, block)) toast(fail)
            PetRuntime.refresh(this)
            render()
        }
        when {
            m.stone -> act.addView(Ui.button(this, "Wake from stone (1 gem)") { change({ game, t -> Engine.revive(game, m.id, t) }, "You need a gem for that.") })
            m.id in g.party -> {
                if (g.party.firstOrNull() != m.id) act.addView(Ui.button(this, "Make lead") { change({ game, _ -> Engine.makeLead(game, m.id) }, "") })
                act.addView(Ui.button(this, "Send to the den", Ui.LIGHT) { change({ game, _ -> Engine.toDen(game, m.id) }, "The last monster in the party has to stay.") })
            }
            else -> act.addView(Ui.button(this, "Join the party") { change({ game, _ -> Engine.toParty(game, m.id) }, "The party is full. Send someone to the den first.") })
        }
        act.addView(Ui.button(this, "Rename", Ui.LIGHT) { rename(m) })
        act.addView(Ui.button(this, "Share", Ui.LIGHT) { ShareCard.share(this, m.id) })
        page.addView(act)
        return page
    }

    private fun rename(m: Monster) {
        val et = EditText(this)
        et.setText(m.name)
        et.filters = arrayOf(InputFilter.LengthFilter(14))
        et.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Rename ${m.name}")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                GameStore.edit(this) { g, _ -> Engine.rename(g, m.id, et.text.toString()) }
                PetRuntime.refresh(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ nest

    private fun nestPage(g: Game): View {
        val page = Ui.col(this)
        page.addView(header(g, "NEST"))

        val r = reveal
        if (r != null) {
            val c = Ui.card(this)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setBackgroundResource(R.drawable.tile_on)
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14))
            val m = r.monster
            c.addView(Ui.sprite(this, Art.portrait(this, m.species, m.stage, Art.HAPPY), 140))
            val title = when {
                r.isNew -> "New: ${m.sp.name}"
                r.star -> "Another ${m.sp.name}"
                else -> "Another ${m.sp.name}"
            }
            c.addView(Ui.h(this, title).apply { contentDescription = "hatched ${m.sp.name}" })
            val detail = when {
                r.isNew -> "${m.sp.rarity.label}. ${m.sp.blurb}" + (if (m.id in g.party) " It joined your party." else " The party is full, so it is waiting in the den.")
                r.star -> "${m.name} absorbed its twin and earned a star (${m.stars} of ${Rules.MAX_STARS}). Each star is 10% faster growth." + (if (r.revived) " It also woke from stone." else "")
                else -> "${m.name} already has every star, so the egg came with ${r.chow} chow instead."
            }
            c.addView(Ui.body(this, detail), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            c.addView(Ui.button(this, "Meet ${m.name}") { reveal = null; detailId = m.id; tab = 1; scrolls[1] = 0; render() })
            page.addView(c)
        }

        val eggs = Ui.card(this)
        eggs.addView(Ui.h(this, "Eggs"))
        if (g.nest.isEmpty()) {
            eggs.addView(Ui.body(this, "No eggs waiting. One gem buys one egg. What is inside is decided when you buy it."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        } else {
            eggs.addView(Ui.body(this, "Tap an egg three times to crack it."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            val tiles = g.nest.mapIndexed { i, e ->
                tile(Ui.sprite(this, Art.egg(this, e.species, e.taps), 84), Dex.get(e.species).rarity.label, "${3 - e.taps} taps to go", "", false).apply {
                    contentDescription = "egg $i"
                    setOnClickListener {
                        val h = GameStore.edit(this@MainActivity) { game, t -> Engine.tapEgg(game, i, t) }
                        buzz(h != null)
                        if (h != null) {
                            reveal = h
                            scrolls[2] = 0
                            PetRuntime.refresh(this@MainActivity)
                        }
                        render()
                    }
                }
            }
            eggs.addView(grid(tiles, 3))
        }
        eggs.addView(Ui.button(this, "Buy an egg (1 gem)") {
            val e = GameStore.edit(this) { game, t -> Engine.buyEgg(game, t) }
            if (e == null) toast("No gems. Merge a pull request, keep a streak, or level up.") else reveal = null
            render()
        })
        page.addView(eggs)

        val odds = Ui.card(this)
        odds.addView(Ui.h(this, "What is in an egg"))
        odds.addView(Ui.text(this, "Common     60%   5 species\nRare       28%   4 species\nEpic       10%   2 species\nLegendary   2%   1 species", 13f, R.color.ink, mono = true), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        odds.addView(Ui.body(this, "Bad luck has a limit. Rare or better within ${5 - g.sinceRare} eggs, epic or better within ${20 - g.sinceEpic}, legendary within ${60 - g.sinceLegendary}. A species you already own gives that monster a star. Gems cannot be bought."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        page.addView(odds)

        val spend = Ui.card(this)
        spend.addView(Ui.h(this, "Other things gems do"))
        spend.addView(Ui.button(this, "Throw a feast (${Rules.FEAST_COST} gems)", Ui.LIGHT) { feast() })
        spend.addView(Ui.body(this, "Fills every belly, gives the party a day of growth and wakes every statue."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        spend.addView(Ui.button(this, "Stock the pantry (1 gem)", Ui.LIGHT) {
            val ok = GameStore.edit(this) { game, t -> Engine.stockUp(game, t, GameStore.tz(t)) }
            if (!ok) toast("No gems to spend.")
            PetRuntime.refresh(this)
            render()
        })
        spend.addView(Ui.body(this, "Adds ${Rules.STOCK_CHOW} chow. Handy before a holiday."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        page.addView(spend)

        val earn = Ui.card(this)
        earn.addView(Ui.h(this, "How to earn gems"))
        earn.addView(Ui.body(this, "1 for each merged pull request, up to 3 a day\n1 for each keeper level\n1 for shipping on 5 days in a week\nStreaks: day 7, 14, 30, 60, 100, 180 and 365"), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        page.addView(earn)
        return page
    }

    // ------------------------------------------------------------------ log

    private fun logPage(g: Game, now: Long): View {
        val page = Ui.col(this)
        page.addView(header(g, "LOG"))

        val keeper = Ui.card(this)
        keeper.addView(Ui.h(this, "Keeper level ${g.level}: ${Rules.title(g.level)}"))
        if (g.level < 10) {
            val a = Rules.LEVEL_CHOW[g.level - 1]
            val b = Rules.LEVEL_CHOW[g.level]
            keeper.addView(Ui.bar(this, (g.lifetimeChow - a).toFloat() / (b - a), R.color.gold))
            keeper.addView(Ui.body(this, "${b - g.lifetimeChow} more chow to ${Rules.title(g.level + 1)}. Each level is a gem and 20 more pantry space."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        } else {
            keeper.addView(Ui.body(this, "The top rank. ${g.lifetimeChow} chow earned in total."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        }
        keeper.addView(Ui.text(this,
            "Streak        ${g.streak} days (best ${g.bestStreak})\n" +
            "Days shipped  ${g.shipDays}\n" +
            "Pushes        ${g.pushes}\n" +
            "Merged PRs    ${g.merges}\n" +
            "Chow earned   ${g.lifetimeChow}\n" +
            "Hatched       ${g.hatched}\n" +
            "Feasts        ${g.feasts}\n" +
            "Falls         ${g.falls}", 13f, R.color.ink, mono = true), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
        page.addView(keeper)

        val time = Ui.card(this)
        time.addView(Ui.h(this, "What happened"))
        if (g.log.isEmpty()) time.addView(Ui.body(this, "Nothing yet. Push something."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        for (e in g.log.take(40)) {
            val r = Ui.row(this)
            r.gravity = Gravity.TOP
            r.addView(Ui.text(this, Fmt.ago(now - e.at), 11f, R.color.ink_soft, mono = true), Ui.lp(Ui.dp(this, 64), Ui.WRAP))
            r.addView(Ui.text(this, e.text, 13f), Ui.lp(0, Ui.WRAP, 1f))
            time.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
        }
        page.addView(time)

        val hall = Ui.card(this)
        hall.addView(Ui.h(this, "Hall of fame"))
        if (g.hall.isEmpty()) hall.addView(Ui.body(this, "Titans and fallen hordes are remembered here."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        for (e in g.hall.take(20)) {
            hall.addView(Ui.text(this, e.text, 13f), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 8)))
            val t = Ui.text(this, "${Fmt.ago(now - e.at)}  |  Share", 11f, R.color.monster, bold = true, mono = true)
            t.setOnClickListener { ShareCard.share(this, -1, e.text) }
            hall.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP, top = Ui.dp(this, 2)))
        }
        page.addView(hall)
        return page
    }
}
