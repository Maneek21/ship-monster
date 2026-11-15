package dev.shipmonster.app

import android.view.Gravity
import android.widget.LinearLayout
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Ev
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Rules

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup: GitHub account, the monster lock, notifications, and the test lab. */
class SettingsActivity : Activity() {

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<EditText>(R.id.et_username).setText(Prefs.username(this))
        findViewById<Button>(R.id.btn_save_github).setOnClickListener { saveGitHub() }
        findViewById<Button>(R.id.btn_remove_token).setOnClickListener {
            Prefs.setToken(this, null)
            toast("Token removed.")
            render()
        }

        findViewById<Button>(R.id.btn_usage).setOnClickListener { open { startActivity(LockAccess.usageAccessIntent()) } }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { open { startActivity(LockAccess.overlayIntent(this)) } }
        findViewById<Button>(R.id.btn_apps).setOnClickListener { chooseApps() }
        findViewById<Button>(R.id.btn_app_settings).setOnClickListener {
            open {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }

        findViewById<Button>(R.id.btn_commit).setOnClickListener { fakeShip(false) }
        findViewById<Button>(R.id.btn_merge).setOnClickListener { fakeShip(true) }
        findViewById<Button>(R.id.btn_skip6).setOnClickListener { skip(6) }
        findViewById<Button>(R.id.btn_skip24).setOnClickListener { skip(24) }
        findViewById<Button>(R.id.btn_reset_clock).setOnClickListener {
            GameStore.resetClock(this)
            changed("Pet clock is back on real time.")
        }
        findViewById<Button>(R.id.btn_reset_all).setOnClickListener { confirmResetAll() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system permission screen: arm the lock if everything is now in place.
        PetRuntime.refresh(this)
        render()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun open(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            toast("This phone has no screen for that setting. Look for it in system settings.")
        }
    }

    private fun changed(msg: String) {
        PetRuntime.refresh(this)
        render()
        toast(msg)
    }

    // GitHub

    private fun saveGitHub() {
        if (busy) return
        val name = findViewById<EditText>(R.id.et_username).text.toString().trim().removePrefix("@")
        val tokenField = findViewById<EditText>(R.id.et_token)
        val token = tokenField.text.toString().trim()
        if (!SyncLogic.validUsername(name)) {
            toast("That does not look like a GitHub username.")
            return
        }
        hideKeyboard()
        Prefs.setUsername(this, name)
        if (token.isNotEmpty()) {
            if (!Prefs.setToken(this, token)) {
                toast("This phone could not store the token securely. Nothing was saved for the token.")
            }
            tokenField.setText("")
        }
        busy = true
        render()
        val app = applicationContext
        Thread {
            val outcome = try {
                GitHubSync.run(app)
            } catch (e: Exception) {
                SyncOutcome("Sync failed. Will retry.", 0, false)
            }
            runOnUiThread {
                busy = false
                PetRuntime.refresh(app)
                if (!isDestroyed) {
                    render()
                    toast(outcome.message)
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val focused = currentFocus ?: return
        imm.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    // Monster lock

    private fun chooseApps() {
        val apps = LockAccess.lockableApps(this)
        if (apps.isEmpty()) {
            toast("No lockable apps found.")
            return
        }
        val current = Prefs.blockedApps(this)
        val firstTime = current.isEmpty()
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { i ->
            apps[i].pkg in current || (firstTime && apps[i].pkg in LockAccess.SUGGESTED)
        }
        AlertDialog.Builder(this)
            .setTitle("Apps the monster locks")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val picked = HashSet<String>()
                for (i in apps.indices) if (checked[i]) picked.add(apps[i].pkg)
                Prefs.setBlockedApps(this, picked)
                if (picked.isEmpty() && Prefs.lockEnabled(this)) {
                    Prefs.setLockEnabled(this, false)
                    LockService.stop(this)
                }
                PetRuntime.refresh(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onLockSwitch(on: Boolean) {
        if (!on) {
            Prefs.setLockEnabled(this, false)
            LockService.stop(this)
            render()
            return
        }
        val missing = when {
            !LockAccess.hasUsageAccess(this) -> "Grant usage access first."
            !LockAccess.hasOverlay(this) -> "Allow display over other apps first."
            Prefs.blockedApps(this).isEmpty() -> "Choose at least one app to lock first."
            else -> null
        }
        if (missing != null) {
            toast(missing)
            render()
            return
        }
        Prefs.setLockEnabled(this, true)
        PetRuntime.refresh(this)
        render()
        // The guard starts a moment later; show its real state once it has.
        findViewById<View>(R.id.sw_lock).postDelayed({ if (!isDestroyed) render() }, 800)
        toast("Lock armed. It only acts while the pet is a monster.")
    }

    // Notifications

    private fun onNotifySwitch(on: Boolean) {
        Prefs.setNotifyEnabled(this, on)
        if (on && !Notifier.canPost(this)) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                toast("Turn notifications on for Ship Monster in system settings.")
            }
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // Test lab

    private fun fakeShip(merge: Boolean) {
        val msg = GameStore.edit(this) { g, now ->
            if (g.needsStarter) return@edit "Pick a starter egg on the home screen first."
            val evs = Engine.ship(g, now, merge, GameStore.tz(now), "test/lab")
            val hatched = evs.firstOrNull { it.kind == Ev.HATCHED }?.monster
            val evolved = evs.firstOrNull { it.kind == Ev.EVOLVED }?.monster
            when {
                hatched != null -> "${hatched.name} hatched."
                evs.any { it.kind == Ev.CALMED } -> "The horde calmed down. Apps unlocked."
                evolved != null -> "${evolved.name} grew into a ${evolved.stageName}."
                merge -> "Fake PR merged. ${g.gems} gems, ${g.pantry} chow."
                else -> "Fake push. ${g.pantry} chow in the pantry."
            }
        }
        changed(msg)
    }

    private fun skip(hours: Int) {
        val before = Engine.mood(GameStore.load(this))
        GameStore.skip(this, hours)
        val g = GameStore.load(this)
        val after = Engine.mood(g)
        changed(
            if (before == after) "Skipped ${hours}h." else when (after) {
                Mood.RESTLESS -> "The horde is restless."
                Mood.FERAL -> "The horde went feral."
                Mood.EMPTY -> "The horde has fallen."
                else -> "Skipped ${hours}h."
            }
        )
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("Reset everything?")
            .setMessage("This deletes every monster, the pantry, the gems and the sync history. Your GitHub name, token and lock choices stay.")
            .setPositiveButton("Reset") { _, _ ->
                GameStore.reset(this)
                Prefs.resetSync(this)
                Prefs.setOnboarded(this, false)
                changed("Reset. Start again from the home screen.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Rest days, hibernation and extra test buttons are built in code and added above the version line.

    private var extra: LinearLayout? = null

    private fun renderExtra() {
        val root = findViewById<TextView>(R.id.tv_version).parent as LinearLayout
        extra?.let { root.removeView(it) }
        val box = Ui.col(this)
        extra = box
        root.addView(box, root.indexOfChild(findViewById(R.id.tv_version)))
        val g = GameStore.load(this)
        val now = GameStore.now(this)

        val rest = Ui.card(this)
        rest.addView(Ui.h(this, "Rest days"))
        rest.addView(Ui.body(this, "On a rest day the horde clock is paused and bellies do not drain. Shipping still pays. A streak carries across rest days."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
        val days = Ui.row(this)
        val names = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        for (d in 0..6) {
            val on = d in g.restDays
            val t = Ui.text(this, names[d], 13f, R.color.ink, bold = true, mono = true)
            t.gravity = Gravity.CENTER
            t.setBackgroundResource(if (on) R.drawable.tile_on else R.drawable.tile)
            t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
            t.contentDescription = "rest ${names[d]} " + (if (on) "on" else "off")
            t.setOnClickListener {
                GameStore.edit(this) { game, _ ->
                    if (d in game.restDays) game.restDays.remove(d) else if (game.restDays.size < 3) game.restDays.add(d)
                    Unit
                }
                PetRuntime.refresh(this)
                renderExtra()
            }
            days.addView(t, Ui.lp(0, Ui.WRAP, 1f, start = if (d > 0) Ui.dp(this, 4) else 0))
        }
        rest.addView(days, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
        rest.addView(Ui.text(this, "Up to three days a week.", 12f, R.color.ink_soft), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 6)))
        box.addView(rest)

        val hib = Ui.card(this)
        hib.addView(Ui.h(this, "Hibernate"))
        if (Engine.hibernating(g, now)) {
            val left = ((g.hibernateUntil - now) / Rules.H).toInt() + 1
            hib.addView(Ui.body(this, "The horde is asleep for another ${Fmt.hours(left)}. Nothing drains and nothing locks."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            hib.addView(Ui.button(this, "Wake the horde now", Ui.LIGHT) {
                GameStore.edit(this) { game, t -> Engine.wake(game, t) }
                PetRuntime.refresh(this)
                renderExtra()
            })
        } else {
            hib.addView(Ui.body(this, "Going on holiday or off sick? Put the horde to sleep for up to ${Rules.MAX_HIBERNATE_DAYS} days. Once every ${Rules.HIBERNATE_COOLDOWN_DAYS} days, and not while feral."), Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 4)))
            val r = Ui.row(this)
            for ((i, n) in intArrayOf(3, 7, 14).withIndex()) {
                r.addView(Ui.button(this, "$n days", Ui.LIGHT) {
                    val ok = GameStore.edit(this) { game, t -> Engine.hibernate(game, t, n) }
                    if (!ok) toast("Not available right now. It needs a calm horde and ${Rules.HIBERNATE_COOLDOWN_DAYS} days since the last time.")
                    PetRuntime.refresh(this)
                    renderExtra()
                }, Ui.lp(0, Ui.WRAP, 1f, start = if (i > 0) Ui.dp(this, 8) else 0))
            }
            hib.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP, top = Ui.dp(this, 10)))
        }
        box.addView(hib)

        val lab = Ui.card(this)
        lab.addView(Ui.h(this, "More test lab"))
        lab.addView(Ui.button(this, "Add 5 gems", Ui.LIGHT) {
            GameStore.edit(this) { game, _ -> game.gems += 5 }
            changed("Added 5 gems.")
        })
        lab.addView(Ui.button(this, "Add 100 growth to the party", Ui.LIGHT) {
            GameStore.edit(this) { game, _ -> for (m in game.partyMonsters()) { m.gp += 100; if (m.stage > m.peak) m.peak = m.stage } }
            changed("The party grew.")
        })
        box.addView(lab)
        box.addView(Ui.space(this, 12))
    }

    // Render

    private fun setSwitch(id: Int, value: Boolean, listener: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean -> listener(isChecked) }
    }

    private fun render() {
        val user = Prefs.username(this)
        val hasToken = Prefs.hasToken(this)
        findViewById<TextView>(R.id.tv_github_state).text = when {
            busy -> "Checking GitHub..."
            user.isEmpty() -> "Not connected."
            hasToken -> "Connected as @$user with a token. Private repos count."
            else -> "Connected as @$user. Public repos you own count."
        }
        findViewById<EditText>(R.id.et_token).hint =
            if (hasToken) "Token saved. Type a new one to replace it" else getString(R.string.hint_token)
        findViewById<View>(R.id.btn_remove_token).visibility = if (hasToken) View.VISIBLE else View.GONE

        val usage = LockAccess.hasUsageAccess(this)
        val overlay = LockAccess.hasOverlay(this)
        val apps = Prefs.blockedApps(this).size
        findViewById<TextView>(R.id.tv_usage).text =
            if (usage) "Usage access: granted" else "Usage access: needed to see which app is open"
        findViewById<View>(R.id.btn_usage).visibility = if (usage) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tv_overlay).text =
            if (overlay) "Display over other apps: granted" else "Display over other apps: needed to show the lock"
        findViewById<View>(R.id.btn_overlay).visibility = if (overlay) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tv_apps).text =
            if (apps == 0) "Apps to lock: none chosen" else "Apps to lock: $apps chosen"

        setSwitch(R.id.sw_lock, Prefs.lockEnabled(this)) { onLockSwitch(it) }
        findViewById<TextView>(R.id.tv_lock_state).text = when {
            !Prefs.lockEnabled(this) -> "Off. The horde still goes feral, but nothing locks."
            !LockAccess.ready(this) -> "On, but something above is missing, so it cannot lock yet."
            LockService.running -> "Armed and running."
            else -> "Armed. It starts the next time this app is opened."
        }

        setSwitch(R.id.sw_notify, Prefs.notifyEnabled(this) && Notifier.canPost(this)) { onNotifySwitch(it) }

        renderExtra()
        val offset = GameStore.clockOffset(this)
        findViewById<TextView>(R.id.tv_test_state).text =
            if (offset == 0L) "Game clock: real time." else "Game clock: ${Fmt.hours((offset / Rules.H).toInt())} ahead of real time."

        findViewById<TextView>(R.id.tv_version).text = "Ship Monster ${BuildConfig.VERSION_NAME}. Open source, GPL-3.0."
    }
}
