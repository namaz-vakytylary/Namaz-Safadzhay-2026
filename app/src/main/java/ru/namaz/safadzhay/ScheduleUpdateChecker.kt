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
    private val executor: Executor = worker
) {
    constructor(context: Context) : this({
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        ScheduleRepository.get(app).sync(online).changed
    })

    private val started = AtomicBoolean(false)
    @Volatile private var onChanged: () -> Unit = {}

    fun checkOnce(onChanged: () -> Unit) {
        this.onChanged = onChanged
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (check()) this.onChanged()
            } catch (error: Exception) {
                Log.w("ScheduleUpdate", "Background check failed: ${error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        internal val worker = Executors.newSingleThreadExecutor()
    }
}
