package ru.namaz.safadzhay

import android.app.Activity
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.rustore.sdk.appupdate.listener.InstallStateUpdateListener
import ru.rustore.sdk.appupdate.manager.RuStoreAppUpdateManager
import ru.rustore.sdk.appupdate.model.*
import ru.rustore.sdk.core.tasks.Task

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuStoreAppUpdateSourceTest {
    // Opaque SDK constructors are internal: use reflective fixtures, never production reflection.
    private fun info(a: Activity, code: Long = 100, availability: Int = UpdateAvailability.UPDATE_AVAILABLE,
        status: Int = InstallStatus.UNKNOWN, pkg: String = a.packageName): AppUpdateInfo =
        AppUpdateInfo::class.java.declaredConstructors.single { it.parameterCount == 14 }.apply { isAccessible = true }.newInstance(
            1L, "Namaz", "", 100L, pkg, "new", code, status, availability, 0, "2026-10-12", false, "Изменения RuStore", null
        ) as AppUpdateInfo
    private fun state(status: Int, pkg: String, bytes: Long = 0, total: Long = 0) =
        InstallState::class.java.declaredConstructors.single { it.parameterCount == 6 }.apply { isAccessible = true }.newInstance(status, pkg, bytes, total, 0, null) as InstallState
    private fun idle() { repeat(3) { shadowOf(Looper.getMainLooper()).idle() } }
    private class Manager(var info: AppUpdateInfo) : RuStoreAppUpdateManager {
        var checks = 0; var installs = 0; var started: AppUpdateInfo? = null
        var options: AppUpdateOptions? = null; var listener: InstallStateUpdateListener? = null; var throwStart = false
        override fun getAppUpdateInfo(): Task<AppUpdateInfo> { checks++; return Task.create { setTaskSuccessResult(info) } }
        override fun startUpdateFlow(appUpdateInfo: AppUpdateInfo, appUpdateOptions: AppUpdateOptions): Task<Int> {
            if (throwStart) error("Unavailable"); started = appUpdateInfo; options = appUpdateOptions
            return Task.create { setTaskSuccessResult(Activity.RESULT_OK) }
        }
        override fun completeUpdate(appUpdateOptions: AppUpdateOptions): Task<Unit> {
            installs++; options = appUpdateOptions; return Task.create { setTaskSuccessResult(Unit) }
        }
        override fun registerListener(listener: InstallStateUpdateListener) { this.listener = listener }
        override fun unregisterListener(listener: InstallStateUpdateListener) { assertSame(this.listener, listener); this.listener = null }
    }
    private fun withActivity(block: (Activity) -> Unit) {
        val c = Robolectric.buildActivity(Activity::class.java).setup(); try { block(c.get()) } finally { c.pause().stop().destroy() }
    }
    @Test fun missingStoreSkipsSdkInitializationAndReturnsNoUpdate() = withActivity { a ->
        val source = RuStoreAppUpdateSource.create(a)
        assertFalse(source is RuStoreAppUpdateSource)
        var callback = false
        source.check({ assertNull(it); callback = true }, { fail("Missing store is a normal state") })
        assertTrue(callback); source.close()
    }
    @Test fun availabilityAndPackageIdentityComeFromStore() = withActivity { a ->
        val m = Manager(info(a)); val source = RuStoreAppUpdateSource(a, m); var value: StoreAppUpdate? = null
        source.check({ value = it }, { fail() }); idle(); assertEquals("new", value!!.versionName); assertEquals("Изменения RuStore", value!!.whatsNew)
        for (candidate in listOf(info(a, availability = UpdateAvailability.UPDATE_NOT_AVAILABLE), info(a, code = 1), info(a, pkg = "another.package"))) {
            m.info = candidate; source.check({ value = it }, { fail() }); idle(); assertNull(value)
        }
        source.close(); assertNull(m.listener)
    }
    @Test fun downloadAlwaysGetsFreshSingleUseInfo() = withActivity { a ->
        val m = Manager(info(a)); val source = RuStoreAppUpdateSource(a, m)
        source.check({}, { fail() }); idle(); val fresh = info(a, code = 101); m.info = fresh
        source.download { fail() }; idle(); assertEquals(2, m.checks); assertSame(fresh, m.started); assertEquals(AppUpdateType.SILENT, m.options!!.appUpdateType)
        m.info = info(a, code = 102); source.download { fail() }; idle(); assertEquals(3, m.checks); assertSame(m.info, m.started); source.close()
    }
    @Test fun hundredPercentBytesDoesNotAuthorizeInstall() = withActivity { a ->
        val m = Manager(info(a)); val source = RuStoreAppUpdateSource(a, m); var value: StoreAppUpdate? = null
        source.onState = { value = it }; source.check({}, { fail() }); idle()
        m.listener!!.onStateUpdated(state(InstallStatus.DOWNLOADING, a.packageName, 100, 100)); idle(); assertEquals(100, value!!.percent)
        var failed = false; source.install { failed = true }; assertTrue(failed); assertEquals(0, m.installs)
        m.listener!!.onStateUpdated(state(InstallStatus.DOWNLOADING, a.packageName)); idle(); assertNull(value!!.percent)
        m.listener!!.onStateUpdated(state(InstallStatus.DOWNLOADED, a.packageName)); idle(); source.install { fail() }; idle()
        assertEquals(1, m.installs); assertEquals(AppUpdateType.FLEXIBLE, m.options!!.appUpdateType); source.close()
    }
    @Test fun downloadedRecoveryAsyncFailuresAndClosedCallbacks() = withActivity { a ->
        val m = Manager(info(a, status = InstallStatus.DOWNLOADED)); val source = RuStoreAppUpdateSource(a, m); var value: StoreAppUpdate? = null
        source.onState = { value = it }; source.download { fail() }; idle(); assertEquals(AppUpdateStage.READY, value!!.stage); assertNull(m.started)
        m.info = info(a); m.throwStart = true; var failed = false; source.download { failed = true }; idle(); assertTrue(failed)
        source.close(); source.check({ fail("Callback after close") }, { fail("Callback after close") }); idle()
    }
}
