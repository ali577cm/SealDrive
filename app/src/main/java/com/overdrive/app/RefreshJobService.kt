package com.overdrive.app

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/** Periodic background refresh for the widget (runs only while a widget exists; no notification). */
class RefreshJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                if (SealDriveWidget.hasWidgets(this)) SealDriveWidget.refreshNow(this)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
