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

            ScheduleRepository
                .get(app)
                .sync(online, progress)
                .changed
        }
    )

    private val started = AtomicBoolean(false)

    @Volatile
    private var onChanged: () -> Unit = {}

    @Volatile
    private var onProgress: (Int, Int) -> Unit = { _, _ -> }

    @Volatile
    private var onFinished: () -> Unit = {}

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

        executor.execute {
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
                this.onFinished()
            }
        }
    }

    companion object {
        internal val worker = Executors.newSingleThreadExecutor()
    }
}
    
