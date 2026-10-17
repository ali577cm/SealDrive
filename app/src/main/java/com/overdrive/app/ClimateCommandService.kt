package com.overdrive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

/** Short-lived foreground service that carries out one climate widget tap, so Android does not cut it off while the car wakes up. */
class ClimateCommandService : Service() {

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("command", "Car commands", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "command")
            .setSmallIcon(R.drawable.ic_thermo)
            .setContentTitle("Sending to the car…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(44, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        else startForeground(44, n)
        thread {
            try {
                SealDriveClimateWidget().runToggle(this)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
