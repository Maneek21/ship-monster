package dev.shipmonster.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

class AppEntry(val pkg: String, val label: String)

/** Permission checks and app lists for the monster lock. */
object LockAccess {

    /** Popular time sinks, ticked by default in the picker when installed. */
    val SUGGESTED = setOf(
        "com.instagram.android", "com.google.android.youtube", "com.twitter.android",
        "com.facebook.katana", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.reddit.frontpage", "com.snapchat.android", "com.netflix.mediaclient",
        "com.instagram.barcelona", "in.startv.hotstar", "com.amazon.avod.thirdpartyclient"
    )

    /** Never offered for locking: calls, payments, maps, settings, and the tools you ship with. */
    private val ALWAYS_OPEN = setOf(
        "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.dialer",
        "com.google.android.dialer", "com.google.android.apps.messaging", "com.google.android.apps.maps",
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.github.android", "com.termux", "com.anthropic.claude"
    )

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    /** True when the lock is switched on and has everything it needs to work. */
    fun ready(c: Context): Boolean =
        Prefs.lockEnabled(c) && hasUsageAccess(c) && hasOverlay(c) && Prefs.blockedApps(c).isNotEmpty()

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName))

    private fun protectedPackages(c: Context): Set<String> {
        val out = HashSet(ALWAYS_OPEN)
        out.add(c.packageName)
        try {
            c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out.add(it) }
        } catch (e: Exception) {
            // no telephony on this device
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(c)?.let { out.add(it) }
        } catch (e: Exception) {
            // no SMS on this device
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        c.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { out.add(it) }
        return out
    }

    /** Apps with a launcher icon, minus the ones that must always open. Sorted by name. */
    fun lockableApps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val skip = protectedPackages(c)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = mutableListOf<AppEntry>()
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in skip || !seen.add(pkg)) continue
            out.add(AppEntry(pkg, info.loadLabel(pm).toString()))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    fun labelOf(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
