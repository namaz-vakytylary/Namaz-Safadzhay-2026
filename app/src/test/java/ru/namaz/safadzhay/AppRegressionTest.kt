package ru.namaz.safadzhay

import android.Manifest
import android.app.*
import android.content.*
import android.net.*
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.security.MessageDigest
import java.time.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
class AppRegressionTest {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: ScheduleRepository
    private val responses = mutableMapOf<String, ByteArray>()
    private val calls = mutableListOf<String>()
    private val zone = ZoneId.of("Europe/Moscow")
    private val tomorrow get() = LocalDate.now(zone).plusDays(1)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("settings", 0); prefs.edit().clear().commit()
        app.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-schedules") }?.forEach { it.delete() }
        TestNetwork.offline(app)
        repo = ScheduleRepository(app, { url, _ ->
            val path = url.removePrefix(ScheduleRepository.BASE_URL)
            synchronized(calls) { calls.add(path) }
            responses[path] ?: throw java.io.IOException("HTTP 503")
        })
        ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun online() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }
    private fun waitWorkers(a: MainActivity) {
        ScheduleUpdateChecker.worker.submit {}.get(20, TimeUnit.SECONDS)
        ReflectionHelpers.getField<ExecutorService>(a, "alarmExecutor").submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun invoke(a: MainActivity, method: String) = ReflectionHelpers.callInstanceMethod<Unit>(a, method)
    private fun publish(version: Int, fajr: String) {
        val date = tomorrow.toString(); val year = tomorrow.year
        val cities = JSONArray()
        listOf("safadzhay", "moscow").forEach { city ->
            val day = JSONObject().put("date", date).put("fajr", if(city == "moscow") "06:30" else fajr)
                .put("zuhr", "12:00").put("asr", "14:00").put("maghrib", "16:00").put("isha", "18:00")
            cities.put(JSONObject().put("id",city).put("timeZone","Europe/Moscow").put("days",JSONArray().put(day)))
        }
        val body = JSONObject().put("schemaVersion",1).put("year",year).put("version",version)
            .put("coverage",JSONObject().put("from",date).put("to",date).put("completeYear",false))
            .put("cities",cities).toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry=JSONObject().put("year",year).put("version",version).put("path","$year.json")
            .put("cityIds",JSONArray().put("safadzhay").put("moscow")).put("dateFrom",date).put("dateTo",date)
            .put("completeYear",false).put("daysPerCity",1).put("bytes",body.size).put("sha256",hash)
        responses["$year.json"] = body
        responses["manifest.json"] = JSONObject().put("schemaVersion",1).put("schedules",JSONArray().put(entry)).toString().toByteArray()
    }
    private fun fajrAlarm(): ShadowAlarmManager.ScheduledAlarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
        val intent=shadowOf(it.operation).savedIntent
        intent.getStringExtra("date")==tomorrow.toString() && intent.getStringExtra("key")=="fajr"
    }
    private fun assertTime(hour: Int, minute: Int) = assertEquals(tomorrow.atTime(hour,minute).atZone(zone).toInstant().toEpochMilli(),fajrAlarm().triggerAtMs)

    @Test fun updateWaitsUntilNotificationPermissionDialogCloses() {
        online()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get())
            assertTrue(calls.isEmpty())
            c.get().onRequestPermissionsResult(7001,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            waitWorkers(c.get())
            assertEquals(listOf("manifest.json"), calls)
        } finally { c.destroy() }
    }

    @Test fun progressStagesAndTwoSecondCompletionMessageArePreserved() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get())
            val looper = shadowOf(Looper.getMainLooper())
            for (value in listOf(12, 68, 88, 100)) {
                ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showScheduleUpdateDialog",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 2027),
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, value))
            }
            looper.idle()
            val shownAt = ReflectionHelpers.getField<Long>(c.get(), "scheduleUpdateShownAt")
            fun advanceTo(offset: Long) {
                // Dialog creation can advance Robolectric's clock; use its actual start time.
                val remaining = shownAt + offset - android.os.SystemClock.uptimeMillis()
                assertTrue("Missed progress checkpoint", remaining >= 0)
                looper.idleFor(Duration.ofMillis(remaining))
            }
            fun percent() = ReflectionHelpers.getField<TextView>(c.get(), "scheduleUpdatePercent").text.toString()
            assertEquals("12%", percent())
            advanceTo(400); assertEquals("40%", percent())
            advanceTo(900); assertEquals("68%", percent())
            advanceTo(1600); assertEquals("88%", percent())
            advanceTo(2300); assertEquals("100%", percent())
            assertEquals("Расписание обновлено",
                ReflectionHelpers.getField<TextView>(c.get(), "scheduleUpdateStatus").text.toString())
            advanceTo(4299)
            assertNotNull(ReflectionHelpers.getField<Dialog?>(c.get(), "scheduleUpdateDialog"))
            advanceTo(4300)
            assertNull(ReflectionHelpers.getField<Dialog?>(c.get(), "scheduleUpdateDialog"))
        } finally { c.destroy() }
    }

    @Test fun eachLaunchChecksOnceIncludingRetryButResumeAndRecreationDoNot() {
        online()
        var c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        waitWorkers(c.get()); assertEquals(listOf("manifest.json"),calls) // First HTTP failure.
        c.pause().resume(); waitWorkers(c.get()); assertEquals(1,calls.size)
        c.recreate(); waitWorkers(c.get()); assertEquals(1,calls.size)
        c.pause().stop().destroy()
        responses["manifest.json"]="{\"schemaVersion\":1,\"schedules\":[]}".toByteArray()
        c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        waitWorkers(c.get()); assertEquals(listOf("manifest.json","manifest.json"),calls)
        invoke(c.get(),"showSettingsDialog")
        ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel").dismiss()
        c.pause().resume(); waitWorkers(c.get()); assertEquals(2,calls.size)
        invoke(c.get(),"showQiblaCompass")
        ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel").dismiss()
        c.pause().resume(); waitWorkers(c.get()); assertEquals(2,calls.size)
        assertEquals(0,ShadowToast.shownToastCount())
        c.pause().stop().destroy()
    }
    @Test fun remoteRevisionRebuildsAlarmsAndCityLeadAndPrayerSelectionStillWork() {
        prefs.edit().putBoolean("notifications_enabled",true).commit()
        publish(1,"05:30"); assertTrue(repo.sync(true).changed)
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get()); assertTime(5,25)
            publish(2,"05:32"); assertTrue(repo.sync(true).changed)
            invoke(c.get(),"onScheduleUpdated"); waitWorkers(c.get()); assertTime(5,27)
            prefs.edit().putInt("notify_before_min",10).commit()
            invoke(c.get(),"schedulePrayerNotifications"); waitWorkers(c.get()); assertTime(5,22)
            ReflectionHelpers.setField(c.get(),"selectedCity","Москва"); prefs.edit().putString("city","Москва").commit()
            invoke(c.get(),"schedulePrayerNotifications"); waitWorkers(c.get()); assertTime(6,20)
            prefs.edit().putBoolean("notify_fajr",false).commit()
            invoke(c.get(),"schedulePrayerNotifications"); waitWorkers(c.get())
            assertFalse(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any { shadowOf(it.operation).savedIntent.getStringExtra("key")=="fajr" })
        } finally { c.destroy() }
    }
    @Test fun stablePackagePreservesDefaultNotificationsAndExplicitOptOut() {
        assertEquals("ru.namaz.safadzhay",app.packageName)
        publish(1,"05:30");repo.sync(true)
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get()); assertTime(5,25)
            prefs.edit().putBoolean("notifications_enabled",false).commit()
            invoke(c.get(),"schedulePrayerNotifications"); waitWorkers(c.get())
            assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
            prefs.edit().putBoolean("notifications_enabled",true).commit()
            invoke(c.get(),"schedulePrayerNotifications");waitWorkers(c.get());assertTime(5,25)
        } finally { c.destroy() }
    }
    private fun texts(v: View): List<String> = buildList {
        if(v is TextView) add(v.text.toString())
        if(v is ViewGroup) for(i in 0 until v.childCount) addAll(texts(v.getChildAt(i)))
    }
    @Test fun settingsAndMissingDateHaveNoUpdateControlsOrDownloadStatus() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get()); invoke(c.get(),"showSettingsDialog")
            val settings=ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel")
            val forbidden=listOf("Обновление расписания","Проверить обновления","загрузить","JSON","Последняя проверка")
            val visible=texts(settings.window!!.decorView).joinToString("\n")
            assertFalse(visible,forbidden.any { visible.contains(it,true) })
            ReflectionHelpers.setField(c.get(),"selectedDate",LocalDate.of(2027,1,1))
            ReflectionHelpers.setField(c.get(),"scheduleTabSelected",true);invoke(c.get(),"update")
            val main=texts(c.get().window.decorView).joinToString("\n")
            assertFalse(main,forbidden.any { main.contains(it,true) })
           assertTrue(main.contains("Расписание пока недоступно"))
assertTrue(main.contains("Для этой даты время намаза ещё не загружено."))
        } finally { c.destroy() }
    }
    @Test fun accessibilityActionsHaveNamesAndDateRemainsReadable() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            waitWorkers(c.get())
            val date=ReflectionHelpers.getField<TextView>(c.get(),"dateText")
            assertTrue(date.contentDescription.toString().contains(date.text.toString()))
            invoke(c.get(),"showSettingsDialog")
            val root=ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel").window!!.decorView
            fun back(v:View):TextView? {
                if(v is TextView && v.text=="‹") return v
                if(v is ViewGroup) for(i in 0 until v.childCount) back(v.getChildAt(i))?.let { return it }
                return null
            }
            assertEquals("Назад",back(root)!!.contentDescription)
        } finally { c.destroy() }
    }

    @Test fun tatarTogglePersistsAndNotificationUsesPreference() {
        var c=Robolectric.buildActivity(MainActivity::class.java).create()
        waitWorkers(c.get());invoke(c.get(),"showSettingsDialog")
        fun findSwitch(v: View): Switch? {
            if(v is Switch && v.contentDescription=="Названия намазов на татарском") return v
            if(v is ViewGroup) for(i in 0 until v.childCount) findSwitch(v.getChildAt(i))?.let { return it }
            return null
        }
        val toggle=findSwitch(ReflectionHelpers.getField<Dialog>(c.get(),"settingsPanel").window!!.decorView)!!
        toggle.isChecked=false;assertFalse(prefs.getBoolean("show_tatar_names",true));c.destroy()
        c=Robolectric.buildActivity(MainActivity::class.java).create();waitWorkers(c.get())
        try {
            val rows=ReflectionHelpers.getField<LinearLayout>(c.get(),"prayerList")
            assertTrue((rows.getChildAt(0) as ViewGroup).let { row ->
                val box=row.getChildAt(1) as ViewGroup;box.getChildAt(1).visibility==View.GONE })
            prefs.edit().putBoolean("notifications_enabled",true).commit()
            fun fire() { PrayerNotificationReceiver().onReceive(app,Intent().putExtra("key","fajr").putExtra("prayer","Фаджр")
                .putExtra("tatar","Иртәнге намаз").putExtra("time","05:30").putExtra("date",LocalDate.now(zone).toString())) }
            fire();val nm=shadowOf(app.getSystemService(NotificationManager::class.java))
            assertEquals("Напоминание: Фаджр",nm.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
            prefs.edit().putBoolean("show_tatar_names",true).commit();fire()
            assertEquals("Напоминание: Фаджр (Иртәнге намаз)",nm.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
                } finally { c.destroy() }
    }

    @Test
fun resumeReactsToExactAlarmPermissionChange() {
    val c = Robolectric.buildActivity(MainActivity::class.java)
        .create()
        .start()
        .resume()

    try {
        waitWorkers(c.get())

        val before = ReflectionHelpers.getField<String>(
            c.get(),
            "lastAlarmSignature"
        )

        assertTrue(before.isNotBlank())

        c.pause()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        c.resume()

        waitWorkers(c.get())

        val after = ReflectionHelpers.getField<String>(
            c.get(),
            "lastAlarmSignature"
        )

        assertNotEquals(before, after)
    } finally {
        c.pause().stop().destroy()
    }
}
@Test
    fun notificationPermissionResultPersistsGrantAndDenial() {

    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    ShadowAlarmManager.setCanScheduleExactAlarms(false)

    val c = Robolectric.buildActivity(MainActivity::class.java).create()

    try {
        c.get().onRequestPermissionsResult(
            7001,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED)
        )

        assertTrue(prefs.getBoolean("notifications_enabled", false))
        assertTrue(prefs.getBoolean("exact_alarm_prompted", false))

        c.get().onRequestPermissionsResult(
            7001,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)
        )

        assertTrue(prefs.contains("notifications_enabled"))
        assertFalse(prefs.getBoolean("notifications_enabled", true))
        } finally {
        c.destroy()
    }
}
}
