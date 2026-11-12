package dev.shipmonster.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** User settings and sync bookkeeping. The pet itself lives in PetStore. */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // GitHub account

    fun username(c: Context): String = p(c).getString("username", "") ?: ""

    fun setUsername(c: Context, v: String) {
        if (v == username(c)) return
        p(c).edit().putString("username", v).apply()
        resetSync(c)
    }

    fun hasToken(c: Context): Boolean = !p(c).getString("token_enc", null).isNullOrEmpty()

    fun token(c: Context): String? = TokenVault.decrypt(p(c).getString("token_enc", null))

    /** Stores the token encrypted with a key held in the Android Keystore. Returns false if it could not be stored. */
    fun setToken(c: Context, v: String?): Boolean {
        if (v.isNullOrEmpty()) {
            p(c).edit().remove("token_enc").apply()
        } else {
            val enc = TokenVault.encrypt(v) ?: return false
            p(c).edit().putString("token_enc", enc).apply()
        }
        clearSyncCache(c)
        return true
    }

    // Monster lock

    fun lockEnabled(c: Context): Boolean = p(c).getBoolean("lock_enabled", false)
    fun setLockEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("lock_enabled", v).apply()

    fun blockedApps(c: Context): Set<String> = HashSet(p(c).getStringSet("blocked_apps", emptySet()) ?: emptySet())
    fun setBlockedApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("blocked_apps", HashSet(v)).apply()

    // Notifications

    fun notifyEnabled(c: Context): Boolean = p(c).getBoolean("notify_enabled", true)
    fun setNotifyEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("notify_enabled", v).apply()

    /** The last pet status we told the user about, so each change is announced once. */
    fun lastStatus(c: Context): String = p(c).getString("last_status", "") ?: ""
    fun setLastStatus(c: Context, v: String) = p(c).edit().putString("last_status", v).apply()

    // Sync bookkeeping

    fun firstSyncDone(c: Context): Boolean = p(c).getBoolean("first_sync_done", false)
    fun lastSeen(c: Context): Long = p(c).getLong("last_seen", 0L)
    fun lastSyncAt(c: Context): Long = p(c).getLong("last_sync_at", 0L)
    fun lastSyncMessage(c: Context): String = p(c).getString("last_sync_message", "") ?: ""
    fun reposEtag(c: Context): String? = p(c).getString("repos_etag", null)
    fun lastRepo(c: Context): String? = p(c).getString("last_repo", null)

    fun repoPushed(c: Context): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val o = JSONObject(p(c).getString("repo_pushed", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getLong(k)
        } catch (e: Exception) {
            // start over with an empty map
        }
        return out
    }

    fun saveSync(
        c: Context,
        lastSeen: Long,
        message: String,
        reposEtag: String?,
        repoPushed: Map<String, Long>,
        lastRepo: String?
    ) {
        val o = JSONObject()
        for ((k, v) in repoPushed) o.put(k, v)
        p(c).edit()
            .putBoolean("first_sync_done", true)
            .putLong("last_seen", lastSeen)
            .putLong("last_sync_at", System.currentTimeMillis())
            .putString("last_sync_message", message)
            .putString("repos_etag", reposEtag)
            .putString("repo_pushed", o.toString())
            .putString("last_repo", lastRepo)
            .apply()
    }

    fun saveSyncFailure(c: Context, message: String) {
        p(c).edit()
            .putLong("last_sync_at", System.currentTimeMillis())
            .putString("last_sync_message", message)
            .apply()
    }

    /** Forget cached repo data so the next sync looks at everything again. Keeps the last-seen marker. */
    fun clearSyncCache(c: Context) {
        p(c).edit().remove("repos_etag").remove("repo_pushed").apply()
    }

    /** Start sync from scratch, as on a new account. */
    fun resetSync(c: Context) {
        p(c).edit()
            .remove("first_sync_done").remove("last_seen").remove("last_sync_at")
            .remove("last_sync_message").remove("repos_etag").remove("repo_pushed").remove("last_repo")
            .apply()
    }
}
