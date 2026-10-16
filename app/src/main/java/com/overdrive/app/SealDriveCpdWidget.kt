package com.overdrive.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/**
 * 1x1 pet-mode toggle (this replaced the old "CPD off" button, so the class keeps its name and any widget already
 * on the home screen keeps working). Tap to start or stop [PetService].
 */
class SealDriveCpdWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.overdrive.app.PET_TOGGLE"

        private fun render(ctx: Context): RemoteViews {
            val on = PetMode.isActive(ctx)
            val note = PetMode.note(ctx)
            val (title, text, color) = if (on)
                Triple("Pet mode ON", PetMode.cabin(ctx)?.let { "cabin $it" } ?: note.ifEmpty { "Starting…" }, "#2BD4A7")
            else
                Triple("Pet mode", if (note.startsWith("Pet mode stopped")) "STOPPED - battery low" else "OFF · tap to start",
                    if (note.startsWith("Pet mode stopped")) "#FF8A8A" else "#8FA6BD")
            val tap = PendingIntent.getBroadcast(ctx, 5,
                Intent(ctx, SealDriveCpdWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_pet).apply {
                setTextViewText(R.id.pet_title, title)
                setTextViewText(R.id.pet_status, text)
                setTextColor(R.id.pet_status, Color.parseColor(color))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        /** Redraws every pet button (state change, or the background setting changed). */
        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveCpdWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx))
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (PetMode.isActive(ctx)) PetMode.stop(ctx) else PetMode.start(ctx)
        redraw(ctx)
    }
}
