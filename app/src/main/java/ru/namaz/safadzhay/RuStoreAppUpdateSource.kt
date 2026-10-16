package ru.namaz.safadzhay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
import ru.rustore.sdk.appupdate.listener.InstallStateUpdateListener
import ru.rustore.sdk.appupdate.manager.RuStoreAppUpdateManager
import ru.rustore.sdk.appupdate.manager.factory.RuStoreAppUpdateManagerFactory
import ru.rustore.sdk.appupdate.model.*

/** Only the official RuStore SDK downloads and installs APKs. */
internal class RuStoreAppUpdateSource(
    private val activity: Activity,
    private val manager: RuStoreAppUpdateManager = RuStoreAppUpdateManagerFactory.create(activity.applicationContext)
) : AppUpdateSource {
    companion object {
        fun create(activity: Activity): AppUpdateSource {
            val installed = listOf("ru.vk.store", "ru.vk.store.qa").any { name ->
                runCatching { activity.packageManager.getPackageInfo(name, 0) }.isSuccess
            }
            return if (installed) RuStoreAppUpdateSource(activity) else UnavailableStoreUpdateSource()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var current: StoreAppUpdate? = null
    private var listening = false
    override var onState: ((StoreAppUpdate) -> Unit)? = null
    private val listener = InstallStateUpdateListener { state -> dispatch {
        val previous = current ?: return@dispatch
        if (state.packageName != activity.packageName) return@dispatch
        val next = previous.copy(stage = stage(state.installStatus), downloadedBytes = state.bytesDownloaded, totalBytes = state.totalBytesToDownload)
        current = next; onState?.invoke(next)
    } }
    private fun dispatch(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun attempt(failure: () -> Unit, block: () -> Unit) = dispatch { runCatching(block).onFailure { failure() } }
    private fun stage(status: Int) = when (status) {
        InstallStatus.DOWNLOADED -> AppUpdateStage.READY
        InstallStatus.DOWNLOADING -> AppUpdateStage.DOWNLOADING
        InstallStatus.PENDING -> AppUpdateStage.WAITING
        InstallStatus.FAILED -> AppUpdateStage.FAILED
        else -> AppUpdateStage.AVAILABLE // unknown/interrupted/cancelled
    }
    private fun snapshot(info: AppUpdateInfo): StoreAppUpdate? {
        val installed = PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
        if (info.packageName != activity.packageName || info.availableVersionCode <= installed ||
            info.updateAvailability !in listOf(UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)) return null
        val next = StoreAppUpdate(info.availableVersionCode, info.availableVersionName, info.whatsNew, stage(info.installStatus))
        val previous = current
        return if (previous?.versionCode == next.versionCode && previous.stage == next.stage)
            next.copy(downloadedBytes = previous.downloadedBytes, totalBytes = previous.totalBytes) else next
    }
    private fun listen() { if (!listening) { manager.registerListener(listener); listening = true } }
    override fun check(success: (StoreAppUpdate?) -> Unit, failure: () -> Unit) {
        if (closed) return
        runCatching { manager.getAppUpdateInfo().addOnSuccessListener { info -> attempt(failure) {
            current = snapshot(info); if (current != null) listen(); success(current)
        } }.addOnFailureListener { dispatch(failure) } }.onFailure { dispatch(failure) }
    }
    override fun download(failure: () -> Unit) {
        if (closed) return
        // AppUpdateInfo is single-use: obtain fresh availability on every start/retry.
        runCatching { manager.getAppUpdateInfo().addOnSuccessListener { info -> attempt(failure) {
            val next = snapshot(info); current = next
            if (next == null) { failure(); return@attempt }
            listen()
            if (next.stage !in listOf(AppUpdateStage.AVAILABLE, AppUpdateStage.FAILED)) {
                onState?.invoke(next); return@attempt
            }
            if (info.updateAvailability != UpdateAvailability.UPDATE_AVAILABLE) { failure(); return@attempt }
            current = next.copy(stage = AppUpdateStage.WAITING); onState?.invoke(current!!)
            manager.startUpdateFlow(info, AppUpdateOptions.Builder().appUpdateType(AppUpdateType.SILENT).build())
                .addOnSuccessListener { code -> if (code != Activity.RESULT_OK) dispatch(failure) }
                .addOnFailureListener { dispatch(failure) }
        } }.addOnFailureListener { dispatch(failure) } }.onFailure { dispatch(failure) }
    }
    override fun install(failure: () -> Unit) {
        if (closed) return
        if (current?.stage != AppUpdateStage.READY) { failure(); return }
        // RuStore confirms installation and restarts the app in its flexible completion flow.
        runCatching { manager.completeUpdate(AppUpdateOptions.Builder().appUpdateType(AppUpdateType.FLEXIBLE).build())
            .addOnFailureListener { dispatch(failure) } }.onFailure { dispatch(failure) }
    }
    override fun close() {
        closed = true; if (listening) runCatching { manager.unregisterListener(listener) }
        listening = false; onState = null; main.removeCallbacksAndMessages(null)
    }
}

/** Absence of a store must cause no SDK initialization, RPC, background work or UI. */
private class UnavailableStoreUpdateSource : AppUpdateSource {
    override var onState: ((StoreAppUpdate) -> Unit)? = null
    override fun check(success: (StoreAppUpdate?) -> Unit, failure: () -> Unit) { success(null) }
    override fun download(failure: () -> Unit) { failure() }
    override fun install(failure: () -> Unit) { failure() }
    override fun close() { onState = null }
}
