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
        val tz = GameStore.tz(now)
        // while a deadline is waiting on a sync, look again in a few minutes
        TickReceiver.scheduleNext(app, if (GameStore.holding) 5 * 60_000L else Engine.nextWakeAt(g, now, tz) - now)
        LockService.ensure(app, Engine.lockActive(g, now, tz))
        Notifier.streakReminder(app, g, now, tz)
    }
}
