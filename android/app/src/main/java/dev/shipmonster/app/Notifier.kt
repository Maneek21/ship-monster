package dev.shipmonster.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import dev.shipmonster.app.core.Ev
import dev.shipmonster.app.core.Event
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Rules
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Notification channels and the few messages the pet sends. */
object Notifier {
    private const val CH_PET = "pet"
    private const val CH_GUARD = "guard"
    const val ID_GUARD = 10
    private const val ID_PET = 11
    private const val ID_REARM = 12

    private fun manager(c: Context): NotificationManager = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(c: Context) {
        val m = manager(c)
        if (m.getNotificationChannel(CH_PET) == null) {
            val ch = NotificationChannel(CH_PET, "Pet news", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "When your pet gets hungry, turns monster, turns back, or dies."
            m.createNotificationChannel(ch)
        }
        if (m.getNotificationChannel(CH_GUARD) == null) {
            val ch = NotificationChannel(CH_GUARD, "Monster lock", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shown while the monster lock is armed."
            m.createNotificationChannel(ch)
        }
    }

    fun canPost(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager(c).areNotificationsEnabled()
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun build(c: Context, channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c))

    /** The ongoing notification every foreground service must show. */
    fun guard(c: Context): Notification {
        ensureChannels(c)
        return build(c, CH_GUARD, "Monster lock is armed", "Apps only lock while the horde is feral.")
            .setOngoing(true)
            .build()
    }

    /** Announces what happened while nobody was looking. One notification per kind of news. */
    fun onEvents(c: Context, g: Game, events: List<Event>) {
        if (!Prefs.notifyEnabled(c) || !canPost(c)) return
        val bank = GameStore.lines(c)
        val lockOn = Prefs.lockEnabled(c) && LockAccess.ready(c)
        for (e in events) {
            val m = e.monster
            val name = m?.name ?: "The horde"
            val seed = g.lastTick / 60_000 + e.kind.ordinal
            val pair: Pair<String, String>? = when (e.kind) {
                Ev.RESTLESS -> "The horde is restless" to "$name: \"${bank.say("restless", m, g, seed)}\""
                Ev.FERAL -> "The horde went feral" to
                    (if (lockOn) "Your chosen apps are locked until you ship." else "Ship something to calm it down.") +
                    (if (e.n > 0) " Your ${e.n}-day streak is gone." else "")
                Ev.FALL -> "The horde has fallen" to e.text
                Ev.CALMED -> "The horde is calm again" to "$name: \"${bank.say("calmed", m, g, seed)}\""
                Ev.EVOLVED -> "$name grew into a ${Rules.STAGE_NAMES[e.n]}" to "\"${bank.say("evolved", m, g, seed)}\""
                Ev.HATCHED -> "$name hatched" to "\"${bank.say("hatched", m, g, seed)}\""
                Ev.BENCHED -> "$name went to the den" to "It went hungry for a full day. Ship more, or keep a smaller party."
                Ev.MILESTONE -> "${e.n}-day streak" to "The horde is impressed. A gem is waiting in the nest."
                else -> null
            }
            if (pair == null) continue
            ensureChannels(c)
            manager(c).notify(ID_PET + 10 + e.kind.ordinal, build(c, CH_PET, pair.first, pair.second).setAutoCancel(true).build())
        }
    }

    /** The lock should be running but Android would not let it start from the background. */
    fun askToReopen(c: Context) {
        if (!canPost(c)) return
        ensureChannels(c)
        val n = build(c, CH_PET, "Open Ship Monster to re-arm the lock", "Android stopped the lock in the background. One tap brings it back.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager(c).notify(ID_REARM, n)
    }

    fun clearReopen(c: Context) = manager(c).cancel(ID_REARM)
}
