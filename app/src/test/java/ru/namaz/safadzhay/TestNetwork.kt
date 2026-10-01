package ru.namaz.safadzhay

import android.content.Context
import android.net.ConnectivityManager
import org.robolectric.Shadows.shadowOf

internal object TestNetwork {
    fun offline(context: Context) {
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).setActiveNetworkInfo(null)
    }
}
