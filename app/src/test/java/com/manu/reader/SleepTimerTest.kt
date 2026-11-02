package com.manu.reader

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SleepTimerTest {
    @Test fun expiresOnceAndClearsDeadline() {
        var expired = 0
        var active: Long? = null
        val timer = SleepTimer({ active = it }, { expired++ })
        timer.schedule(1)
        assertNotNull(active)
        shadowOf(Looper.getMainLooper()).idleFor(59, TimeUnit.SECONDS)
        assertEquals(0, expired)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, expired)
        assertNull(active)
        assertNull(timer.deadline)
    }

    @Test fun reschedulingReplacesOldTimerAndCancellationPreventsExpiry() {
        var expired = 0
        val timer = SleepTimer({}, { expired++ })
        timer.schedule(1)
        timer.schedule(30)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES)
        assertEquals(0, expired)
        timer.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MINUTES)
        assertEquals(0, expired)
        assertNull(timer.deadline)
    }

    @Test fun rejectsOutOfRangeDurations() {
        val timer = SleepTimer({}, {})
        for (minutes in listOf(0, 31)) {
            try {
                timer.schedule(minutes)
                fail("Duration should have been rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}