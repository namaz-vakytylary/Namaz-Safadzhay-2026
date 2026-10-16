package ru.namaz.safadzhay

import android.content.Context
import android.net.ConnectivityManager
import org.robolectric.Shadows.shadowOf

internal object TestNetwork {
    fun published(context: Context) {
        val repo = ScheduleRepository(context, { url, _ ->
            val path = url.removePrefix(ScheduleRepository.BASE_URL)
            javaClass.getResourceAsStream("/schedules/$path")!!.use { it.readBytes() }
        })
        check(repo.sync(true).successful)
        org.robolectric.util.ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
    }
    fun offline(context: Context) {
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).setActiveNetworkInfo(null)
    }
}
