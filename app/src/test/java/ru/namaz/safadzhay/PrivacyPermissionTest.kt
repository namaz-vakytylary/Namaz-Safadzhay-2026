package ru.namaz.safadzhay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import org.xmlpull.v1.XmlPullParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyPermissionTest {
    @Test fun compiledBackupRulesExcludePrivateDataFromCloudAndDeviceTransfer() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val modes = mutableMapOf<String, MutableSet<String>>()
        var mode: String? = null
        app.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "cloud-backup", "device-transfer" -> {
                            mode = xml.name
                            assertNull(modes.put(mode!!, mutableSetOf()))
                        }
                        "exclude" -> {
                            assertEquals(".", xml.getAttributeValue(null, "path"))
                            assertTrue(modes.getValue(requireNotNull(mode)).add(xml.getAttributeValue(null, "domain")))
                        }
                        else -> assertEquals("data-extraction-rules", xml.name)
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == mode) mode = null
                xml.next()
            }
        }
        assertEquals(setOf("cloud-backup", "device-transfer"), modes.keys)
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        modes.values.forEach { assertEquals(domains, it) }
    }

    @Test fun offlineColdStartDoesNotRequestLocationOrLoseSettings() {
        val app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app)
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("notifications_enabled", false).putString("city", "Москва")
            .putBoolean("show_tatar_names", false).apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val requested = shadowOf(controller.get()).lastRequestedPermission?.requestedPermissions.orEmpty()
            assertFalse(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertFalse(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
            assertEquals("Москва", prefs.getString("city", null))
            assertFalse(prefs.getBoolean("show_tatar_names", true))
        } finally { controller.destroy() }
    }
    @Test fun qiblaLocationDenialIsGracefulAndOnlyActualFirstUseRequestsPermission() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("qibla_location_requested").apply()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var status = ""
        val location = QiblaLocationController(activity, { assertNull(it) }, { title, _, _ -> status = title })
        try {
            location.start()
            assertEquals("Нужно местоположение", status)
            assertNull(shadowOf(activity).lastRequestedPermission)
            location.start(askOnFirstUse = true)
            val requested = shadowOf(activity).lastRequestedPermission
            assertEquals(QiblaLocationController.REQUEST_CODE, requested.requestCode)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                requested.requestedPermissions.toSet())
            location.stop()
            shadowOf(Looper.getMainLooper()).idle()
        } finally { controller.pause().stop().destroy() }
    }
}
