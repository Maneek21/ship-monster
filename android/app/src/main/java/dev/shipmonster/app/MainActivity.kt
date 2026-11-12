package dev.shipmonster.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Home: the pet, what happens next, sync status, what is left to set up, and the graveyard. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var joyUntil = 0L
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.main_pet).setOnClickListener { poke() }
        findViewById<Button>(R.id.btn_sync).setOnClickListener { sync(true) }
        findViewById<Button>(R.id.btn_share).setOnClickListener { ShareCard.share(this, null) }
        findViewById<Button>(R.id.btn_hatch).setOnClickListener { hatch() }
        findViewById<Button>(R.id.btn_mercy).setOnClickListener { confirmMercy() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        SyncJobService.schedule(this)
        PetRuntime.refresh(this)
        render()
        val stale = System.currentTimeMillis() - Prefs.lastSyncAt(this) > 2 * 60_000
        if (Prefs.username(this).isNotEmpty() && stale) sync(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun celebrate(ms: Long) {
        joyUntil = System.currentTimeMillis() + ms
        handler.postDelayed({ if (!isDestroyed) render() }, ms + 100)
    }

    private fun sync(manual: Boolean) {
        if (Prefs.username(this).isEmpty()) {
            if (manual) {
                toast("Add your GitHub username first.")
                openSettings()
            }
            return
        }
        if (syncing) return
        syncing = true
        render()
        val app = applicationContext
        Thread {
            val outcome = try {
                GitHubSync.run(app)
            } catch (e: Exception) {
                SyncOutcome("Sync failed. Will retry.", 0, false)
            }
            runOnUiThread {
                syncing = false
                PetRuntime.refresh(app)
                if (!isDestroyed) {
                    if (outcome.fed > 0) celebrate(1800)
                    render()
                    if (manual || outcome.fed > 0) toast(outcome.message)
                }
            }
        }.start()
    }

    private fun poke() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        when (PetEngine.status(s, now)) {
            Status.DEAD -> toast("Rest in peace, ${s.pet.name}.")
            Status.EGG -> toast("The egg wobbles.")
            Status.MONSTER -> toast("It bit you.")
            else -> {
                celebrate(900)
                render()
                toast("${s.pet.name} wiggles.")
            }
        }
    }

    private fun hatch() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        if (PetEngine.hatchNew(s, now)) {
            PetStore.save(this, s)
            PetRuntime.refresh(this)
            render()
            toast("New egg: ${s.pet.name}. Ship something to hatch it.")
        }
    }

    private fun confirmMercy() {
        val name = PetStore.load(this).pet.name
        AlertDialog.Builder(this)
            .setTitle("Mercy unlock?")
            .setMessage("This unlocks your apps right now and kills $name. There is no undo.")
            .setPositiveButton("Kill $name") { _, _ ->
                val now = PetStore.now(this)
                val s = PetStore.load(this)
                if (PetEngine.mercy(s, now)) {
                    PetStore.save(this, s)
                    PetRuntime.refresh(this)
                    render()
                    toast("$name is gone. Apps unlocked.")
                }
            }
            .setNegativeButton("Keep $name", null)
            .show()
    }

    private fun addWidget() {
        val awm = getSystemService(AppWidgetManager::class.java)
        if (awm != null && awm.isRequestPinAppWidgetSupported) {
            awm.requestPinAppWidget(ComponentName(this, PetWidget::class.java), null, null)
        } else {
            toast("Long press your home screen, then Widgets, then Ship Monster.")
        }
    }

    private fun hasWidget(): Boolean {
        val awm = getSystemService(AppWidgetManager::class.java) ?: return false
        return awm.getAppWidgetIds(ComponentName(this, PetWidget::class.java)).isNotEmpty()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun stepButton(text: String, action: () -> Unit): Button {
        val b = Button(this, null, 0, R.style.Btn)
        b.text = text
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun bodyText(text: String): TextView {
        val t = TextView(this, null, 0, R.style.Body)
        t.text = text
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        t.layoutParams = lp
        return t
    }

    private fun renderSetup(name: String) {
        val list = findViewById<LinearLayout>(R.id.setup_list)
        list.removeAllViews()
        var open = 0
        if (Prefs.username(this).isEmpty()) {
            list.addView(stepButton("Connect GitHub") { openSettings() })
            open++
        }
        if (!hasWidget()) {
            list.addView(stepButton("Add the home screen widget") { addWidget() })
            open++
        }
        if (!LockAccess.ready(this)) {
            list.addView(stepButton("Arm the monster lock") { openSettings() })
            open++
        }
        if (Prefs.notifyEnabled(this) && !Notifier.canPost(this)) {
            list.addView(stepButton("Allow notifications") {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else {
                    toast("Turn notifications on for Ship Monster in system settings.")
                }
            })
            open++
        }
        if (open == 0) list.addView(bodyText("All set. $name is watching your pushes."))
    }

    private fun renderGraves(s: PetState) {
        val list = findViewById<LinearLayout>(R.id.grave_list)
        list.removeAllViews()
        findViewById<View>(R.id.grave_empty).visibility = if (s.graveyard.isEmpty()) View.VISIBLE else View.GONE
        for (g in s.graveyard) {
            val t = bodyText(PetEngine.epitaph(g) + " Tap to share.")
            t.setTextColor(getColor(R.color.ink))
            t.setOnClickListener { ShareCard.share(this, g) }
            list.addView(t)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val st = PetEngine.status(s, now)
        val monster = st == Status.MONSTER
        val joy = System.currentTimeMillis() < joyUntil

        findViewById<View>(R.id.main_card).setBackgroundResource(if (monster) R.drawable.card_monster else R.drawable.card)
        findViewById<ImageView>(R.id.main_pet).setImageBitmap(Sprites.forState(s, now, 24, joy))
        val name = findViewById<TextView>(R.id.main_name)
        name.text = PetText.title(s, now)
        name.setTextColor(getColor(if (monster) R.color.on_monster else R.color.ink))
        val pill = findViewById<TextView>(R.id.main_state)
        pill.text = PetText.pill(s, now)
        pill.setTextColor(getColor(PetWidget.stateColor(s, st)))
        val meta = findViewById<TextView>(R.id.main_meta)
        meta.text = PetText.meta(s, now)
        meta.setTextColor(getColor(if (monster) R.color.on_monster_soft else R.color.ink_soft))

        val belly = findViewById<ProgressBar>(R.id.main_belly)
        val full = PetEngine.fullness(s, now)
        belly.progress = Math.round(full * 100).toInt()
        belly.progressTintList = ColorStateList.valueOf(getColor(PetWidget.bellyColor(full)))
        belly.visibility = if (st == Status.EGG || st == Status.DEAD) View.INVISIBLE else View.VISIBLE

        findViewById<TextView>(R.id.main_next).text = PetText.next(s, now)
        val user = Prefs.username(this)
        val lastSync = Prefs.lastSyncAt(this)
        findViewById<TextView>(R.id.main_sync).text = when {
            syncing -> "Checking GitHub..."
            user.isEmpty() -> "GitHub is not connected yet"
            lastSync == 0L -> "@$user, not synced yet"
            else -> "@$user, synced ${PetText.ago(System.currentTimeMillis() - lastSync)}"
        }
        val message = findViewById<TextView>(R.id.main_message)
        val msg = Prefs.lastSyncMessage(this)
        message.text = msg
        message.visibility = if (msg.isEmpty() || user.isEmpty()) View.GONE else View.VISIBLE

        findViewById<View>(R.id.btn_hatch).visibility = if (st == Status.DEAD) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_mercy).visibility = if (monster) View.VISIBLE else View.GONE

        renderSetup(s.pet.name)
        renderGraves(s)
    }
}
