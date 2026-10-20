package ru.namaz.safadzhay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One attempt per activity task launch; retained over configuration recreation. */
internal class ScheduleUpdateChecker(
    private val check: () -> Boolean,
    private val executor: Executor = worker,
    private val progressCheck: (((Int, Int) -> Unit) -> Boolean)? = null
) {

    constructor(context: Context) : this(
        check = { false },
        progressCheck = { progress ->
            val app = context.applicationContext
            val manager =
                app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val online = manager.activeNetwork
                ?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val changed = ScheduleRepository.get(app).sync(online, progress).changed
            if (changed) PrayerAlarmScheduler.worker.execute { kotlin.runCatching { PrayerAlarmScheduler.rebuild(app) } }
            changed
        }
    )

    private val started = AtomicBoolean(false)
    @Volatile private var activeThread: Thread? = null
    private var lastAttempt = Long.MIN_VALUE
    fun cancel() { activeThread?.interrupt() }
    fun detach() { onProgress = { _, _ -> }; onFinished = {}; onChanged = {} }
    fun allowRetryIfDue() {
        if (lastAttempt != Long.MIN_VALUE && android.os.SystemClock.elapsedRealtime() - lastAttempt >= 300_000) allowRetry()
    }

    @Volatile
    private var onChanged: () -> Unit = {}

    @Volatile
    private var onProgress: (Int, Int) -> Unit = { _, _ -> }

    @Volatile
    private var onFinished: () -> Unit = {}

    fun allowRetry() { if (!running.get()) started.set(false) }
    private val running = AtomicBoolean(false)

    fun checkOnce(onChanged: () -> Unit) {
        checkOnce(
            onProgress = { _, _ -> },
            onFinished = {},
            onChanged = onChanged
        )
    }

    fun checkOnce(
        onProgress: (Int, Int) -> Unit,
        onFinished: () -> Unit,
        onChanged: () -> Unit
    ) {
        this.onProgress = onProgress
        this.onFinished = onFinished
        this.onChanged = onChanged

        if (!started.compareAndSet(false, true)) return

        running.set(true)
        lastAttempt = android.os.SystemClock.elapsedRealtime()
        executor.execute {
            activeThread = Thread.currentThread()
            try {
                val changed = progressCheck?.invoke { year, value ->
                    this.onProgress(year, value)
                } ?: check()

                if (changed) {
                    this.onChanged()
                }

            } catch (error: Exception) {
                Log.w(
                    "ScheduleUpdate",
                    "Background check failed: ${error.javaClass.simpleName}"
                )
            } finally {
                activeThread = null
                Thread.interrupted()
                running.set(false)
                this.onFinished()
            }
        }
    }

    companion object {
        internal val worker = Executors.newSingleThreadExecutor()
    }
}
    
