package dev.shipmonster.app

import android.content.Context

/** One place that brings everything in line with the pet's current state. Cheap, safe to call often. */
object PetRuntime {
    fun refresh(c: Context) {
        val app = c.applicationContext
        val now = PetStore.now(app)
        val s = PetStore.load(app)
        val st = PetEngine.status(s, now)

        val prev = Prefs.lastStatus(app)
        if (prev != st.name) {
            Prefs.setLastStatus(app, st.name)
            if (prev.isNotEmpty()) Notifier.onTransition(app, s, prev, st)
        }

        PetWidget.refresh(app)
        TickReceiver.scheduleNext(app, s, now)
        LockService.ensure(app, st == Status.MONSTER)
    }
}
