package com.overdrive.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Shared pet-mode settings and on/off state (used by the service and the widget). */
object PetMode {
    const val CLIMATE_MINUTES = 30     // longest remote climate run Overdrive allows
    const val RESEND_AFTER_MS = 15 * 60_000L
    const val STEP_MS = 5_000L                 // the picture is re-shown every step; lock/AC checks every 6th step (30 s)
    const val MAINTAIN_MS = 6 * 60_000L        // how often the AC / screen picture are refreshed

    private fun prefs(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE)

    fun isActive(c: Context) = prefs(c).getBoolean("active", false)
    fun cabin(c: Context): String? = prefs(c).getString("cabin", null)
    fun note(c: Context): String = prefs(c).getString("note", "") ?: ""

    internal fun edit(c: Context) = prefs(c).edit()
    internal fun p(c: Context) = prefs(c)

    /** Starts pet mode (or does nothing if already running). */
    fun start(ctx: Context) {
        if (isActive(ctx)) return
        CarLog.append(ctx, "PET MODE started")
        edit(ctx).putBoolean("active", true).putBoolean("sawLocked", false).putLong("lastSend", 0L)
            .putInt("fails", 0).putLong("lastMaintain", 0L).putInt("cpdBefore", -1).putString("cabin", null).putString("note", "Starting…").apply()
        ctx.startForegroundService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_START))
        SealDriveCpdWidget.redraw(ctx)
    }

    /** User-requested stop: leaves the AC running (it ends by itself within 30 minutes). */
    fun stop(ctx: Context) {
        if (!isActive(ctx)) return
        ctx.startForegroundService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_STOP))
    }
}

/**
 * Pet mode: keeps the AC at 20 C by re-sending Overdrive's remote climate command until the car is unlocked,
 * the user switches it off, or the battery reaches 30%. Runs as a foreground service (visible notification) so
 * Android does not kill it while the phone is in a pocket; ticks come from a doze-friendly alarm.
 */
class PetService : Service() {

    companion object {
        const val ACTION_START = "com.overdrive.app.PET_START"
        const val ACTION_STOP = "com.overdrive.app.PET_STOP"
        const val ACTION_TICK = "com.overdrive.app.PET_TICK"
        private const val CH_RUN = "pet_run"
        private const val CH_ALERT = "pet_alert"
        private const val ID_RUN = 41
        private const val ID_ALERT = 42
        private const val TICK_MS = 6 * 60_000L        // Android stretches this to ~9 min while the phone dozes
    }

    private val busy = AtomicBoolean(false)
    private val looping = AtomicBoolean(false)
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a service started with startForegroundService must go foreground straight away
        goForeground(PetMode.note(this).ifEmpty { "Starting…" })
        val a = intent?.action
        if (!PetMode.isActive(this) && a != ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (a == ACTION_STOP) {
            thread { finish("Pet mode off", stopAc = false, warn = false) }
            return START_NOT_STICKY
        }
        startLoop()
        return START_STICKY
    }

    /** Checks the car every 30 s until pet mode ends. Holds a CPU wake lock so checks continue with the screen off. */
    private fun startLoop() {
        if (!looping.compareAndSet(false, true)) { thread { tick() }; return }
        if (lock == null) lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydrive:pet").apply { acquire(12 * 3600_000L) }
        thread {
            try {
                var n = 0
                while (PetMode.isActive(this)) {
                    // Overdrive shows the picture for at most 30 s per request, so ask again every few seconds to keep it up
                    if (Config.petPicture(this)) CarCommand.showScreenImage(this)
                    if (n++ % 6 == 0) tick()
                    try { Thread.sleep(PetMode.STEP_MS) } catch (_: InterruptedException) { break }
                }
            } finally {
                looping.set(false)
            }
        }
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun goForeground(text: String) {
        nm().createNotificationChannel(NotificationChannel(CH_RUN, "Pet mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9, Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle("Pet mode ON · $text")
            .setContentText("Keeping the car at ${Config.petTemp(this).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}° until you unlock it")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(0, "Turn off", stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_RUN, n)
    }

    private fun scheduleNext() {
        val pi = PendingIntent.getService(this, 8, Intent(this, PetService::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, pi)
    }

    private fun alert(title: String, text: String) {
        nm().createNotificationChannel(NotificationChannel(CH_ALERT, "Pet mode warnings", NotificationManager.IMPORTANCE_HIGH))
        nm().notify(ID_ALERT, Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build())
    }

    /** One check: stop conditions every time; the AC and screen picture only every few minutes. */
    private fun tick() {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (!PetMode.isActive(this)) return
            val p = PetMode.p(this)
            val s = CarState.read(this)
            CarLog.append(this, "PET check: " + (if (s == null) "no answer" else "locked=${s.locked} ac=${s.acOn} cabin=${s.insideC} soc=${s.soc} doorsOpen=${s.doorsOpen}"))
            if (s == null) {
                val fails = p.getInt("fails", 0) + 1
                PetMode.edit(this).putInt("fails", fails).putString("note", "can't reach car").apply()
                if (fails == 20 || (fails > 20 && (fails - 20) % 40 == 0))        // ~10 min, then every ~20 min
                    alert("Pet mode: can't reach the car",
                        "BYDrive has not been able to talk to the car for a while. Check Tailscale and your signal - the AC may stop soon.")
                update(); scheduleNext(); return
            }
            PetMode.edit(this).putInt("fails", 0).putString("cabin", s.insideC?.let { CarState.fmtTemp(it) }).apply()

            if (s.locked == 1) PetMode.edit(this).putBoolean("sawLocked", true).apply()
            val sawLocked = p.getBoolean("sawLocked", false)
            // you are back: the car was locked and is now unlocked (or a door has been opened)
            if (sawLocked && (s.locked == 2 || s.doorsOpen > 0)) {
                finish("Pet mode ended - car unlocked", stopAc = false, warn = false); return
            }
            if (s.soc != null && s.soc <= Config.petMinSoc(this)) {
                finish("Pet mode stopped - battery ${s.soc.toInt()}%", stopAc = true, warn = true); return
            }

            val now = System.currentTimeMillis()
            if (now - p.getLong("lastMaintain", 0L) >= PetMode.MAINTAIN_MS) {
                PetMode.edit(this).putLong("lastMaintain", now).apply()
                // Child Presence Detection would raise the alarm with the pet inside - keep it off, remember what it was
                if (!Config.petCpdOff(this)) {
                    // left alone
                } else if (s.cpdOn == true) {
                    if (p.getInt("cpdBefore", -1) == -1) PetMode.edit(this).putInt("cpdBefore", 1).apply()
                    CarCommand.setChildPresenceDetection(this, 2)
                } else if (s.cpdOn == false && p.getInt("cpdBefore", -1) == -1) {
                    PetMode.edit(this).putInt("cpdBefore", 0).apply()
                }

                if (s.acOn != true || now - p.getLong("lastSend", 0L) >= PetMode.RESEND_AFTER_MS) {
                    val r = CarCommand.startClimate(this, Config.petTemp(this).toDouble(), PetMode.CLIMATE_MINUTES)
                    if (r.ok) PetMode.edit(this).putLong("lastSend", now).putString("note", "AC on" + (if (r.path.isNotEmpty()) " (${r.path})" else "")).apply()
                    else {
                        PetMode.edit(this).putString("note", "AC failed: ${r.message}").apply()
                        if (s.acOn != true) alert("Pet mode: AC did not start", "The car refused the climate command (${r.message}). Check on your pet.")
                    }
                }
                // raise the brightness once the picture is up (waking the panel can reset it)
                if (Config.petPicture(this) && Config.petBrightness(this) > 0) Thread.sleep(2500)
                val level = Config.petBrightness(this)
                if (Config.petPicture(this) && level > 0) {
                    val b = CarCommand.setScreenBrightness(this, level)
                    PetMode.edit(this).putString("screen", if (b.ok) "brightness set" else "brightness: ${b.message}").apply()
                }
            }
            update(); scheduleNext()
        } catch (_: Exception) {
            scheduleNext()
        } finally {
            busy.set(false)
        }
    }

    private fun update() {
        val c = PetMode.cabin(this)
        goForeground((if (c != null) "cabin $c" else PetMode.note(this)) + (PetMode.p(this).getString("screen", null)?.let { " · $it" } ?: ""))
        SealDriveCpdWidget.redraw(this)
    }

    private fun finish(reason: String, stopAc: Boolean, warn: Boolean) {
        CarLog.append(this, "PET MODE ended: $reason (ac off=$stopAc)")
        val p = PetMode.p(this)
        val cpdBefore = p.getInt("cpdBefore", -1)
        PetMode.edit(this).putBoolean("active", false).putString("note", reason).apply()
        getSystemService(AlarmManager::class.java).cancel(
            PendingIntent.getService(this, 8, Intent(this, PetService::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        lock?.let { if (it.isHeld) it.release() }
        var failedOff = false
        if (stopAc) failedOff = !CarCommand.stopClimate(this).ok
        if (cpdBefore == 1) CarCommand.setChildPresenceDetection(this, 1)     // put Child Presence Detection back
        SealDriveCpdWidget.redraw(this)
        if (warn) alert(reason, "The AC has been switched off" + (if (failedOff) " (could not confirm - check the car)" else "") +
            ". Your pet is no longer being cooled - go back to the car.")
        else if (failedOff) alert("Pet mode off - AC may still be running", "Could not confirm the AC switched off.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
