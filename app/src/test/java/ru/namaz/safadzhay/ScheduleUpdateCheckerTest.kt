package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleUpdateCheckerTest {
    @Test fun queuesBackgroundWorkOnceAndRecreatedScreenGetsCompletion() {
        val queue = mutableListOf<Runnable>()
        var checks = 0; var oldScreen = 0; var newScreen = 0
        val checker = ScheduleUpdateChecker({ checks++; true }, Executor { queue.add(it) })
        checker.checkOnce { oldScreen++ }
        checker.checkOnce { newScreen++ }
        assertEquals(0, checks) // Opening the local screen doesn't wait for the network.
        assertEquals(1, queue.size)
        queue.single().run()
        assertEquals(1, checks); assertEquals(0, oldScreen); assertEquals(1, newScreen)
        checker.checkOnce { newScreen++ }
        assertEquals(1, queue.size)
    }
    @Test fun failureDoesNotNotifyUiAndNewLaunchAttemptsAgain() {
        var calls = 0; var notifications = 0
        val runNow = Executor { it.run() }
        ScheduleUpdateChecker({ calls++; throw java.net.SocketTimeoutException() }, runNow)
            .checkOnce { notifications++ }
        ScheduleUpdateChecker({ calls++; false }, runNow).checkOnce { notifications++ }
        assertEquals(2, calls); assertEquals(0, notifications)
    }
}
