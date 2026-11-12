package dev.shipmonster.app

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * The monster lock.
 *
 * Runs as a foreground service for as long as the lock is switched on, because Android 15 only
 * lets a foreground service start while the app is visible or at boot. It idles until the pet
 * turns monster. Then it checks once a second which app is in front and covers the chosen apps
 * with a full screen overlay. It reads only the name of the app in front, nothing inside it.
 */
class LockService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var foreground: String? = null
    private var lastQuery = 0L
    private var screenOn = true
    private var lastStatus: Status? = null
    private var lastSyncKick = 0L
    private var mercyArmedUntil = 0L
    private var checking = false
    private var note: String? = null

    private val loop = Runnable { step() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) {
                handler.removeCallbacks(loop)
                handler.post(loop)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_GUARD, Notifier.guard(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_GUARD, Notifier.guard(this))
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notifier.clearReopen(this)
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // was never registered
        }
        super.onDestroy()
    }

    private fun step() {
        if (!LockAccess.ready(this)) {
            hideOverlay()
            stopSelf()
            return
        }
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        val st = PetEngine.status(s, now)
        if (st != lastStatus) {
            val firstLook = lastStatus == null
            lastStatus = st
            if (!firstLook) PetRuntime.refresh(this)
        }

        if (st != Status.MONSTER) {
            hideOverlay()
            foreground = null
            lastQuery = 0L
            val next = PetEngine.hoursToNext(s, now)
            val wait = if (next == null) IDLE_MS else minOf(IDLE_MS, (next * PetEngine.H).toLong() + 1_000)
            handler.postDelayed(loop, maxOf(1_000L, wait))
            return
        }

        if (!screenOn) {
            handler.postDelayed(loop, 60_000)
            return
        }

        val front = frontApp()
        val blocked = Prefs.blockedApps(this)
        if (front != null && front in blocked) {
            showOverlay(front, s, now)
        } else if (front != null && overlay != null) {
            hideOverlay()
        }

        // Keep looking for a ship so the lock lifts soon after one lands.
        val real = System.currentTimeMillis()
        if (real - lastSyncKick > SYNC_EVERY_MS) {
            lastSyncKick = real
            checkNow(silent = true)
        }
        handler.postDelayed(loop, 1_000)
    }

    /** Package name of the app most recently brought to the front, from usage events. */
    private fun frontApp(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return foreground
        val end = System.currentTimeMillis()
        val begin = if (lastQuery == 0L) end - 10 * 60_000 else lastQuery - 2_000
        try {
            val events = usm.queryEvents(begin, end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
            }
        } catch (ex: Exception) {
            // usage access was revoked; ready() catches that on the next pass
        }
        lastQuery = end
        return foreground
    }

    private fun showOverlay(pkg: String, s: PetState, now: Long) {
        var v = overlay
        if (v == null) {
            val themed = ContextThemeWrapper(this, R.style.Theme_ShipMonster)
            v = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            v.findViewById<ImageView>(R.id.lock_pet).setImageBitmap(Sprites.forState(s, now, 24))
            v.findViewById<Button>(R.id.lock_home).setOnClickListener { goHome() }
            v.findViewById<Button>(R.id.lock_check).setOnClickListener { checkNow(silent = false) }
            v.findViewById<Button>(R.id.lock_mercy).setOnClickListener { mercy() }
            v.isFocusableInTouchMode = true
            v.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (ev.action == KeyEvent.ACTION_UP) goHome()
                    true
                } else {
                    false
                }
            }
            try {
                getSystemService(WindowManager::class.java).addView(v, lp)
            } catch (e: Exception) {
                return
            }
            v.requestFocus()
            overlay = v
            note = null
            mercyArmedUntil = 0L
        }
        overlayPkg = pkg
        val name = s.pet.name
        val app = LockAccess.labelOf(this, pkg)
        v.findViewById<TextView>(R.id.lock_text).text =
            "$app is closed. $name has not eaten in ${PetText.dur(PetEngine.hoursSinceFed(s, now))}. Push a commit and it turns back."
        v.findViewById<TextView>(R.id.lock_count).text =
            "Dies in ${PetText.dur(PetEngine.hoursToNext(s, now) ?: 0.0)}"
        val noteView = v.findViewById<TextView>(R.id.lock_note)
        noteView.text = if (checking) "Checking GitHub..." else (note ?: "")
        val armed = System.currentTimeMillis() < mercyArmedUntil
        v.findViewById<Button>(R.id.lock_mercy).text = if (armed) "Tap again. $name will die." else "Mercy unlock"
        v.findViewById<TextView>(R.id.lock_fine).text = "Mercy unlock opens $app now and kills $name."
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        overlayPkg = null
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // no launcher answered; removing the overlay is still right
        }
        hideOverlay()
        foreground = null
    }

    private fun checkNow(silent: Boolean) {
        if (checking) return
        checking = true
        if (!silent) note = null
        val app = applicationContext
        Thread {
            val outcome = try {
                GitHubSync.run(app)
            } catch (e: Exception) {
                SyncOutcome("Could not reach GitHub.", 0, false)
            }
            handler.post {
                checking = false
                if (!silent) note = if (outcome.fed > 0) outcome.message else "No new ship found yet. GitHub can take a minute."
                PetRuntime.refresh(app)
                handler.removeCallbacks(loop)
                handler.post(loop)
            }
        }.start()
    }

    private fun mercy() {
        val real = System.currentTimeMillis()
        if (real >= mercyArmedUntil) {
            mercyArmedUntil = real + 4_000
            handler.removeCallbacks(loop)
            handler.post(loop)
            return
        }
        mercyArmedUntil = 0L
        val now = PetStore.now(this)
        val s = PetStore.load(this)
        if (PetEngine.mercy(s, now)) {
            PetStore.save(this, s)
            hideOverlay()
            PetRuntime.refresh(this)
        }
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    companion object {
        private const val IDLE_MS = 10 * 60_000L
        private const val SYNC_EVERY_MS = 5 * 60_000L

        @Volatile
        var running = false
            private set

        /**
         * Starts the guard if the lock is ready and it is not already running. Android only allows
         * this while the app is visible or at boot. If it is refused while the pet is a monster,
         * the user gets a notification asking them to open the app.
         */
        fun ensure(c: Context, monster: Boolean) {
            if (running || !LockAccess.ready(c)) return
            try {
                c.startForegroundService(Intent(c, LockService::class.java))
            } catch (e: Exception) {
                if (monster) Notifier.askToReopen(c)
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, LockService::class.java))
        }
    }
}
