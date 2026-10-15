package com.overdrive.app

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/** Runs [MonthlyReport.checkAndSend] in the background about every 3 hours (it only does anything on the 1st of the month). */
class MonthlyJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try { MonthlyReport.checkAndSend(this) } finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
