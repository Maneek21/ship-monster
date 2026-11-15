package dev.shipmonster.app

import android.content.Context
import dev.shipmonster.app.core.Engine

/** One place that brings everything in line with the game's current state. Cheap, safe to call often. */
object PetRuntime {
    fun refresh(c: Context) {
        val app = c.applicationContext
        val g = GameStore.load(app)
        val now = GameStore.now(app)
        PetWidget.refresh(app)
        TickReceiver.scheduleNext(app, Engine.nextWakeAt(g, now, GameStore.tz(now)) - now)
        LockService.ensure(app, Engine.lockActive(g, now))
    }
}
