package com.overdrive.app

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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Logs every change in the car's state (everything Overdrive reports) while switched on, plus whether the car could be
 * reached at all. Read-only: it only does GET requests. Polling is every 15 s so it does not hammer a sleeping car.
 */
class CarLogService : Service() {

    companion object {
        const val ACTION_STOP = "com.overdrive.app.CARLOG_STOP"
        private const val POLL_MS = 15_000L

        fun start(ctx: Context) {
            CarLog.setOn(ctx, true)
            ctx.startForegroundService(Intent(ctx, CarLogService::class.java))
        }

        fun stop(ctx: Context) {
            CarLog.append(ctx, "--- logging stopped ---")
            CarLog.setOn(ctx, false)
            ctx.stopService(Intent(ctx, CarLogService::class.java))
        }
    }

    private var lock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("carlog", "Car log", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 12, Intent(this, CarLogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "carlog")
            .setSmallIcon(R.drawable.ic_stats)
            .setContentTitle("Logging car activity")
            .setContentText("Tap Stop when you have what you need")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(45, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(45, n)

        if (intent?.action == ACTION_STOP) {
            CarLog.append(this, "--- logging stopped ---")
            CarLog.setOn(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            if (lock == null) lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydrive:carlog").apply { acquire(12 * 3600_000L) }
            thread { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun flatten(prefix: String, v: Any?, out: MutableMap<String, String>) {
        when (v) {
            is JSONObject -> v.keys().forEach { k -> flatten(if (prefix.isEmpty()) k else "$prefix.$k", v.opt(k), out) }
            is JSONArray -> for (i in 0 until v.length()) flatten("$prefix[$i]", v.opt(i), out)
            JSONObject.NULL, null -> out[prefix] = "null"
            else -> out[prefix] = v.toString()
        }
    }

    private fun loop() {
        var prev: Map<String, String>? = null
        var reachable: Boolean? = null
        CarLog.append(this, "--- logging started (every 15 s, changes only) ---")
        while (running && CarLog.on(this)) {
            val auth = CarStatus.auth(this)
            if (auth == null) {
                CarLog.append(this, "NOT SIGNED IN or no address - open BYDrive and sign in")
            } else {
                val (base, cookie) = auth
                val state = CarStatus.getJson(base, "/api/vehicle/state", cookie, 8000)
                val status = CarStatus.getJson(base, "/status", cookie, 8000)
                val now = state != null
                if (reachable != now) {
                    CarLog.append(this, if (now) "REACHABLE: Overdrive answered" else "UNREACHABLE: no answer from Overdrive (car/head unit asleep, or Tailscale/phone connection)")
                    reachable = now
                }
                if (state != null) {
                    val cur = LinkedHashMap<String, String>()
                    flatten("", state, cur)
                    if (status != null) flatten("status", status, cur)
                    val p = prev
                    if (p == null) {
                        CarLog.append(this, "SNAPSHOT: " + cur.entries.joinToString("; ") { "${it.key}=${it.value}" }.take(6000))
                    } else {
                        for ((k, v) in cur) if (p[k] != v) CarLog.append(this, "$k: ${p[k] ?: "-"} -> $v")
                        for (k in p.keys) if (!cur.containsKey(k)) CarLog.append(this, "$k: gone")
                    }
                    prev = cur
                }
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
        running = false
    }
}
