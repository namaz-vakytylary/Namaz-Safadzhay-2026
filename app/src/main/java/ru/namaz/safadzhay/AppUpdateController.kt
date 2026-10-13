package ru.namaz.safadzhay

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat

/** Activity-scoped state machine, separate from all prayer/city/notification state. */
internal class AppUpdateController(
    private val activity: Activity,
    private val source: AppUpdateSource,
    private val readyForPrompt: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    savedState: Bundle? = null,
    private val showReadyNotice: ((() -> Unit) -> Unit) = {},
    private val showSuccess: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val history = AppUpdateHistory(activity.getSharedPreferences("app_updates", 0))
    private var resumed = false
    private var destroyed = false
    private var checking = false
    private var checkedAt: Long? = null
    private var current: StoreAppUpdate? = null
    internal var dialog: AppUpdateDialog? = null
        private set
    private var dismissedInSession = savedState?.getBoolean("app_update_dismissed") == true
    private var restoreOpen = savedState?.getBoolean("app_update_open") == true
    private var installationRequested = false
    private var readyNoticeShown = false
    private val present = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            if (readyForPrompt()) presentIfReady()
            if (history.pendingSuccess() != null ||
                (current != null && !dismissedInSession && dialog == null && (restoreOpen || history.canPrompt(now()))) ||
                (current?.stage == AppUpdateStage.READY && dismissedInSession && !readyNoticeShown))
                handler.postDelayed(this, 1000)
        }
    }
    init {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        history.observeInstalled(PackageInfoCompat.getLongVersionCode(installed), installed.versionName.orEmpty())
        source.onState = { value -> if (!destroyed) {
            current = value; dialog?.submit(value); schedulePresentation()
        } }
    }
    fun resume() {
        resumed = true
        if (!checking && (installationRequested || current?.stage in listOf(AppUpdateStage.READY, AppUpdateStage.WAITING, AppUpdateStage.DOWNLOADING) ||
                checkedAt == null || now() - checkedAt!! >= 900_000)) {
            checking = true
            source.check({ value -> if (!destroyed) {
                checking = false; checkedAt = now(); installationRequested = false; current = value
                if (value == null) { dialog?.dismiss(); dialog = null; restoreOpen = false }
                else dialog?.submit(value)
                schedulePresentation()
            } }, { if (!destroyed) { checking = false; checkedAt = now() } })
        }
        schedulePresentation()
    }
    private fun schedulePresentation() {
        handler.removeCallbacks(present); if (resumed && !destroyed) handler.post(present)
    }
    private fun presentIfReady() {
        history.pendingSuccess()?.let { version -> if (dialog == null) { showSuccess(version); history.successShown() } }
        val value = current ?: return
        if (value.stage == AppUpdateStage.READY && dismissedInSession && !readyNoticeShown) {
            readyNoticeShown = true
            showReadyNotice { if (current?.stage == AppUpdateStage.READY && dialog == null && resumed && readyForPrompt()) {
                dismissedInSession = false; openDialog(current!!, countPrompt = false)
            } }
        }
        if (dialog != null || dismissedInSession || (!restoreOpen && !history.canPrompt(now()))) return
        openDialog(value, countPrompt = !restoreOpen)
        restoreOpen = false
    }
    private fun openDialog(value: StoreAppUpdate, countPrompt: Boolean) {
        val next = AppUpdateDialog(activity, ::primaryAction, { dismissedInSession = true })
        next.submit(value)
        next.setOnDismissListener { dialog = null; dismissedInSession = true }
        dialog = next; next.show()
        if (countPrompt) history.prompted(now())
    }
    internal fun primaryAction() {
        val value = current ?: return
        when (value.stage) {
            AppUpdateStage.READY -> {
                installationRequested = true; current = value.copy(stage = AppUpdateStage.INSTALLING); dialog?.submit(current!!)
                source.install { if (!destroyed) { installationRequested = false; current = value; dialog?.submit(value) } }
            }
            AppUpdateStage.AVAILABLE, AppUpdateStage.FAILED -> {
                current = value.copy(stage = AppUpdateStage.WAITING); dialog?.submit(current!!)
                source.download { if (!destroyed) { current = value.copy(stage = AppUpdateStage.FAILED); dialog?.submit(current!!) } }
            }
            else -> Unit
        }
    }
    fun saveState(state: Bundle) {
        state.putBoolean("app_update_open", dialog?.isShowing == true)
        state.putBoolean("app_update_dismissed", dismissedInSession)
    }
    fun pause() { resumed = false; handler.removeCallbacks(present) }
    fun close() {
        destroyed = true; pause(); handler.removeCallbacksAndMessages(null)
        dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null; source.close()
    }
}
