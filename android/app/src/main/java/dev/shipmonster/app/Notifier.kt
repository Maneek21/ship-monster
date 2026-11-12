package dev.shipmonster.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
        return build(c, CH_GUARD, "Monster lock is armed", "Apps only lock while your pet is a monster.")
            .setOngoing(true)
            .build()
    }

    /** Announces a status change. prev is the status name we last announced. */
    fun onTransition(c: Context, s: PetState, prev: String, now: Status) {
        if (!Prefs.notifyEnabled(c)) return
        val name = s.pet.name
        val lockOn = LockAccess.ready(c)
        val pair: Pair<String, String>? = when (now) {
            Status.HUNGRY -> "$name is hungry" to "24 hours without a ship. Push something."
            Status.MONSTER -> "$name turned into a monster" to
                if (lockOn) "Your chosen apps are locked until you ship." else "Ship something to turn it back."
            Status.DEAD -> "$name has died" to
                if (s.pet.deadCause == Cause.MERCY) "Mercy-unlocked. A new egg is waiting." else "Starved as a monster. A new egg is waiting."
            Status.HAPPY -> if (prev == Status.MONSTER.name) "$name turned back" to "Fed again. Apps unlocked." else null
            Status.EGG -> null
        }
        if (pair == null || !canPost(c)) return
        ensureChannels(c)
        manager(c).notify(ID_PET, build(c, CH_PET, pair.first, pair.second).setAutoCancel(true).build())
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
