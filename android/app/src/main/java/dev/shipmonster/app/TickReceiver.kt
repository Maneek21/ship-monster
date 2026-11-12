package dev.shipmonster.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes the app when the pet is due to change state, and after a reboot. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SyncJobService.schedule(context)
        PetRuntime.refresh(context)
    }

    companion object {
        const val ACTION_TICK = "dev.shipmonster.app.TICK"

        /** Sets an inexact alarm for the next state change. Needs no special permission. */
        fun scheduleNext(c: Context, s: PetState, now: Long) {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                c, 1, Intent(c, TickReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val hours = PetEngine.hoursToNext(s, now)
            if (hours == null) {
                am.cancel(pi)
                return
            }
            val at = System.currentTimeMillis() + (hours * PetEngine.H).toLong() + 5_000
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
