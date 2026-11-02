package com.manu.reader

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class SleepTimer(private val onChanged: (Long?) -> Unit, private val onExpired: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    var deadline: Long? = null
        private set
    private val expire = Runnable {
        deadline = null
        onChanged(null)
        onExpired()
    }

    fun schedule(minutes: Int) {
        require(minutes in 1..30)
        handler.removeCallbacks(expire)
        val duration = minutes * 60_000L
        deadline = SystemClock.elapsedRealtime() + duration
        onChanged(deadline)
        handler.postDelayed(expire, duration)
    }

    fun cancel() {
        handler.removeCallbacks(expire)
        deadline = null
        onChanged(null)
    }
}