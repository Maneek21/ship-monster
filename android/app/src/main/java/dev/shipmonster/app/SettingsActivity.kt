package dev.shipmonster.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
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

        findViewById<Button>(R.id.btn_commit).setOnClickListener { fakeShip(false) }
        findViewById<Button>(R.id.btn_merge).setOnClickListener { fakeShip(true) }
        findViewById<Button>(R.id.btn_skip6).setOnClickListener { skip(6) }
        findViewById<Button>(R.id.btn_skip24).setOnClickListener { skip(24) }
        findViewById<Button>(R.id.btn_reset_clock).setOnClickListener {
            PetStore.resetClock(this)
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
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val name = s.pet.name
        val r = PetEngine.ship(s, now, merge, PetStore.tzOffset(now))
        if (r == null) {
            toast("$name is dead. Hatch a new egg on the home screen.")
            return
        }
        PetStore.save(this, s)
        changed(
            when {
                r.hatched -> "$name hatched."
                r.was == Status.MONSTER -> "$name turned back. Apps unlocked."
                r.evolved != null -> "$name grew into a ${PetText.formLabel(r.evolved)}."
                merge -> "Fake PR merged. Feast."
                else -> "Fake commit. $name ate."
            }
        )
    }

    private fun skip(hours: Int) {
        val before = PetEngine.status(PetStore.load(this), PetStore.now(this))
        PetStore.skip(this, hours)
        val s = PetStore.load(this)
        val after = PetEngine.status(s, PetStore.now(this))
        val name = s.pet.name
        changed(
            if (before == after) "Skipped ${hours}h." else when (after) {
                Status.HUNGRY -> "$name is hungry."
                Status.MONSTER -> "$name turned into a monster."
                Status.DEAD -> "$name has died."
                else -> "Skipped ${hours}h."
            }
        )
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("Reset everything?")
            .setMessage("This deletes the pet, the graveyard and the sync history. Your GitHub name, token and lock choices stay.")
            .setPositiveButton("Reset") { _, _ ->
                PetStore.reset(this)
                Prefs.resetSync(this)
                Prefs.setLastStatus(this, "")
                changed("Reset. A fresh egg is waiting.")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            !Prefs.lockEnabled(this) -> "Off. The pet still turns monster, but nothing locks."
            !LockAccess.ready(this) -> "On, but something above is missing, so it cannot lock yet."
            LockService.running -> "Armed and running."
            else -> "Armed. It starts the next time this app is opened."
        }

        setSwitch(R.id.sw_notify, Prefs.notifyEnabled(this) && Notifier.canPost(this)) { onNotifySwitch(it) }

        val offset = PetStore.clockOffset(this)
        findViewById<TextView>(R.id.tv_test_state).text =
            if (offset == 0L) "Pet clock: real time." else "Pet clock: ${PetText.dur(offset.toDouble() / PetEngine.H)} ahead of real time."

        findViewById<TextView>(R.id.tv_version).text = "Ship Monster ${BuildConfig.VERSION_NAME}. Open source, GPL-3.0."
    }
}
