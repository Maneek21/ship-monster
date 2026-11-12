package dev.shipmonster.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** v0 screen: shows the pet and a set of test buttons that fake commits and skip time. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var joyUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_commit).setOnClickListener { ship(false) }
        findViewById<Button>(R.id.btn_merge).setOnClickListener { ship(true) }
        findViewById<Button>(R.id.btn_skip6).setOnClickListener { skip(6) }
        findViewById<Button>(R.id.btn_skip24).setOnClickListener { skip(24) }
        findViewById<Button>(R.id.btn_mercy).setOnClickListener { mercy() }
        findViewById<Button>(R.id.btn_hatch).setOnClickListener { hatch() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            PetStore.reset(this)
            changed("Reset. A fresh egg is waiting.")
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun ship(merge: Boolean) {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val name = s.pet.name
        val r = PetEngine.ship(s, now, merge, PetStore.tzOffset(now))
        if (r == null) {
            toast("$name is dead. Hatch a new egg first.")
            return
        }
        PetStore.save(this, s)
        joyUntil = System.currentTimeMillis() + 1500
        handler.postDelayed({ render() }, 1600)
        val msg = when {
            r.hatched -> "$name hatched."
            r.was == Status.MONSTER -> "$name turned back. Apps would unlock now."
            r.evolved != null -> "$name grew into a ${PetText.formLabel(r.evolved)}."
            merge -> "PR merged. Feast."
            else -> "Commit pushed. $name ate."
        }
        changed(msg)
    }

    private fun skip(hours: Int) {
        val before = PetEngine.status(PetStore.load(this), PetStore.now(this))
        PetStore.skip(this, hours)
        val s = PetStore.load(this)
        val after = PetEngine.status(s, PetStore.now(this))
        val name = s.pet.name
        val msg = if (before == after) {
            "Skipped ${hours}h."
        } else when (after) {
            Status.HUNGRY -> "$name is hungry."
            Status.MONSTER -> "$name turned into a monster."
            Status.DEAD -> "$name has died."
            else -> "Skipped ${hours}h."
        }
        changed(msg)
    }

    private fun mercy() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val name = s.pet.name
        if (PetEngine.mercy(s, now)) {
            PetStore.save(this, s)
            changed("Mercy unlock used. $name is gone.")
        } else {
            toast("Mercy unlock only works on a monster.")
        }
    }

    private fun hatch() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        if (PetEngine.hatchNew(s, now)) {
            PetStore.save(this, s)
            changed("New egg: ${s.pet.name}.")
        } else {
            toast("The current pet is still alive.")
        }
    }

    private fun changed(msg: String) {
        render()
        PetWidget.refresh(this)
        toast(msg)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val st = PetEngine.status(s, now)
        val joy = System.currentTimeMillis() < joyUntil

        findViewById<ImageView>(R.id.main_pet).setImageBitmap(Sprites.forState(s, now, 24, joy))
        findViewById<TextView>(R.id.main_name).text = PetText.title(s, now)
        val pill = findViewById<TextView>(R.id.main_state)
        pill.text = PetText.pill(s, now)
        pill.setTextColor(getColor(PetWidget.stateColor(s, st)))
        findViewById<TextView>(R.id.main_meta).text = PetText.meta(s, now)

        val belly = findViewById<ProgressBar>(R.id.main_belly)
        belly.progress = Math.round(PetEngine.fullness(s, now) * 100).toInt()
        belly.alpha = if (st == Status.EGG || st == Status.DEAD) 0.25f else 1f

        findViewById<Button>(R.id.btn_commit).isEnabled = st != Status.DEAD
        findViewById<Button>(R.id.btn_merge).isEnabled = st != Status.DEAD
        findViewById<Button>(R.id.btn_mercy).isEnabled = st == Status.MONSTER
        findViewById<Button>(R.id.btn_hatch).isEnabled = st == Status.DEAD

        val graves = findViewById<TextView>(R.id.main_graves)
        graves.text = if (s.graveyard.isEmpty()) {
            getString(R.string.graveyard_empty)
        } else {
            s.graveyard.joinToString("\n") { PetEngine.epitaph(it) }
        }
    }
}
