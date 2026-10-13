package ru.namaz.safadzhay

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("app_updates", 0)
    private val sample = StoreAppUpdate(100, "1.4", "• Улучшения интерфейса\n• Исправления ошибок")
    @Before fun setup() {
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
    private fun withActivity(block: (Activity) -> Unit) {
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        try { block(c.get()) } finally { c.pause().stop().destroy() }
    }
    private class FakeSource(var result: StoreAppUpdate?) : AppUpdateSource {
        override var onState: ((StoreAppUpdate) -> Unit)? = null
        var checks = 0; var downloads = 0; var installs = 0; var closed = false; var checkFails = false
        var downloadFailure: (() -> Unit)? = null; var installFailure: (() -> Unit)? = null
        override fun check(success: (StoreAppUpdate?) -> Unit, failure: () -> Unit) {
            checks++; if (checkFails) failure() else success(result)
        }
        override fun download(failure: () -> Unit) { downloads++; downloadFailure = failure }
        override fun install(failure: () -> Unit) { installs++; installFailure = failure }
        override fun close() { closed = true; onState = null }
        fun emit(value: StoreAppUpdate) { result = value; onState?.invoke(value) }
    }
    @Test fun promptLimitSurvivesRelaunchAndClockRollback() {
        val history = AppUpdateHistory(prefs)
        assertTrue(history.canPrompt(10_000)); history.prompted(10_000)
        assertFalse(AppUpdateHistory(prefs).canPrompt(9999))
        assertFalse(history.canPrompt(10_000 + AppUpdateHistory.DAY - 1))
        assertTrue(history.canPrompt(10_000 + AppUpdateHistory.DAY))
    }
    @Test fun successRequiresActualVersionIncreaseAndIsShownOnce() {
        val history = AppUpdateHistory(prefs)
        history.observeInstalled(36, "test"); assertNull(history.pendingSuccess())
        history.observeInstalled(36, "test"); assertNull(history.pendingSuccess())
        history.observeInstalled(37, "new"); assertEquals("new", AppUpdateHistory(prefs).pendingSuccess())
        history.successShown(); history.observeInstalled(37, "new"); assertNull(history.pendingSuccess())
        history.observeInstalled(35, "old"); assertNull(history.pendingSuccess())
    }
    @Test fun percentagesRequireRealByteTotalsAndHandleLargeSizes() {
        assertNull(sample.percent); assertNull(sample.copy(downloadedBytes = -1, totalBytes = 100).percent)
        assertEquals(42, sample.copy(downloadedBytes = 42, totalBytes = 100).percent)
        assertEquals(100, sample.copy(downloadedBytes = 101, totalBytes = 100).percent)
        assertEquals(50, sample.copy(downloadedBytes = Long.MAX_VALUE / 2, totalBytes = Long.MAX_VALUE).percent)
        assertEquals("Новая версия", sample.copy(versionName = null).versionLabel)
    }
    @Test fun offerWaitsForHomeAndContinueKeepsBackgroundDownload() = withActivity { a ->
        val source = FakeSource(sample); var homeReady = false
        val controller = AppUpdateController(a, source, { homeReady }, { 10_000L }, showSuccess = {})
        controller.resume(); idle(); assertNull(controller.dialog); assertFalse(prefs.contains("last_prompt_at"))
        homeReady = true; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(controller.dialog!!.isShowing)
        controller.primaryAction(); controller.primaryAction(); assertEquals(1, source.downloads)
        source.emit(sample.copy(stage = AppUpdateStage.DOWNLOADING, downloadedBytes = 42, totalBytes = 100))
        walk(controller.dialog!!.findViewById(android.R.id.content)).single { it.tag == "app_update_continue" }.performClick()
        assertNull(controller.dialog); source.emit(sample.copy(stage = AppUpdateStage.READY)); idle()
        assertNull(controller.dialog); assertFalse(source.closed)
        controller.close(); assertTrue(source.closed)
        val next = AppUpdateController(a, FakeSource(sample), { true }, { 11_000L }, showSuccess = {})
        next.resume(); idle(); assertNull(next.dialog); next.close()
    }
    @Test fun unavailableStoreAndNoUpdateDoNotPromptOrConsumeDay() = withActivity { a ->
        for (fail in listOf(false, true)) {
            val source = FakeSource(null).apply { checkFails = fail }
            val c = AppUpdateController(a, source, { true }, { 10_000L }, showSuccess = {})
            c.resume(); idle(); assertNull(c.dialog); assertFalse(prefs.contains("last_prompt_at")); c.close()
        }
    }
    @Test fun installNeedsDownloadedStateAndDoesNotClaimSuccess() = withActivity { a ->
        val source = FakeSource(sample.copy(stage = AppUpdateStage.READY))
        val c = AppUpdateController(a, source, { true }, { 10_000L }, showSuccess = {})
        c.resume(); idle(); c.primaryAction(); c.primaryAction()
        assertEquals(1, source.installs); assertNull(prefs.getString("success_version", null))
        source.installFailure!!.invoke(); c.primaryAction(); assertEquals(2, source.installs)
        c.pause(); c.resume(); idle(); assertEquals(2, source.checks)
        assertTrue(walk(c.dialog!!.findViewById(android.R.id.content)).filterIsInstance<TextView>().any { it.text == "Установить" }); c.close()
    }
    @Test fun retryAndPauseDoNotLoseOffer() = withActivity { a ->
        val source = FakeSource(sample); val c = AppUpdateController(a, source, { true }, { 10_000L }, showSuccess = {})
        c.resume(); c.pause(); idle(); assertNull(c.dialog)
        c.resume(); idle(); c.primaryAction(); source.downloadFailure!!.invoke()
        assertTrue(walk(c.dialog!!.findViewById(android.R.id.content)).filterIsInstance<TextView>().any { it.text == "Повторить" })
        c.primaryAction(); assertEquals(2, source.downloads); c.close()
    }
    @Test fun recreationRestoresOpenWindowWithinDailyLimit() = withActivity { a ->
        var c = AppUpdateController(a, FakeSource(sample), { true }, { 10_000L }, showSuccess = {})
        c.resume(); idle(); val state = Bundle(); c.saveState(state); c.close()
        c = AppUpdateController(a, FakeSource(sample.copy(stage = AppUpdateStage.READY)), { true }, { 10_001L }, savedState = state, showSuccess = {})
        c.resume(); idle(); assertTrue(c.dialog!!.isShowing); assertEquals(10_000L, prefs.getLong("last_prompt_at", 0)); c.close()
    }
    @Test fun readyNoticeAllowsUserToInstallAfterContinueWithoutAnotherDailyOffer() = withActivity { a ->
        val source = FakeSource(sample); var installAction: (() -> Unit)? = null; var homeReady = true
        val c = AppUpdateController(a, source, { homeReady }, { 10_000L },
            showReadyNotice = { installAction = it }, showSuccess = {})
        c.resume(); idle(); c.primaryAction()
        walk(c.dialog!!.findViewById(android.R.id.content)).single { it.tag == "app_update_continue" }.performClick()
        homeReady = false; source.emit(sample.copy(stage = AppUpdateStage.READY)); idle(); assertNull(installAction)
        homeReady = true; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNotNull(installAction); assertNull(c.dialog); assertEquals(0, source.installs)
        installAction!!.invoke(); assertTrue(c.dialog!!.isShowing); assertEquals(10_000L, prefs.getLong("last_prompt_at", 0))
        c.primaryAction(); assertEquals(1, source.installs); c.close()
    }
    @Test fun layoutsWrapInBothThemesAtAllSizesAndLargeFonts() {
        for (dark in listOf(false, true)) for ((width, height) in listOf(320 to 640, 360 to 800, 393 to 873, 412 to 915)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (dark) "night" else "notnight"}-mdpi")
            ThemeSettings.save(app, if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
                RuntimeEnvironment.setFontScale(scale)
                withActivity { a -> for (stage in AppUpdateStage.entries) {
                    val d = AppUpdateDialog(a, {}, {}); d.submit(sample.copy(stage = stage, downloadedBytes = 42, totalBytes = 100)); d.show(); idle()
                    val root = d.findViewById<View>(android.R.id.content); val measureWidth = width - 36
                    root.measure(View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * .85).toInt(), View.MeasureSpec.AT_MOST))
                    root.layout(0, 0, measureWidth, root.measuredHeight)
                    assertTrue(root.height <= height * .85 + 1)
                    val card = walk(root).single { it.tag == "app_update_card" }
                    if (scale == 1f && stage == AppUpdateStage.AVAILABLE) assertTrue("Excessive spacing", card.height <= 510)
                    val colors = AppColors(dark)
                    for (text in walk(root).filterIsInstance<TextView>()) {
                        val background = if (text.tag == "app_update_primary") colors.primary else if ((text.parent as? View)?.tag == "app_update_changes") colors.noticeSurface else colors.surface
                        assertTrue("Contrast: ${text.text}", ColorUtils.calculateContrast(text.currentTextColor, background) >= 4.5)
                        val layout = text.layout!!
                        assertEquals("Truncated ${text.text}", text.text.length, layout.getLineEnd(layout.lineCount - 1))
                        assertTrue("Clipped ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                        for (line in 0 until layout.lineCount) {
                            assertEquals(0, layout.getEllipsisCount(line))
                            assertTrue("Clipped width ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 2)
                        }
                    }
                    val logo = walk(root).filterIsInstance<ImageView>().single(); assertNull(logo.imageTintList); assertEquals(logo.width, logo.height)
                    val progress = walk(root).filterIsInstance<ProgressBar>().singleOrNull()
                    if (stage == AppUpdateStage.DOWNLOADING) { assertFalse(progress!!.isIndeterminate); assertEquals(42, progress.progress) }
                    if (stage == AppUpdateStage.WAITING) assertTrue(progress!!.isIndeterminate)
                    if (scale == 1f && width == 360 || scale == 2f && width == 320) {
                        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(image))
                        File("build/reports/theme-ui/app-update").apply { mkdirs() }.resolve("${if (dark) "dark" else "light"}-$stage-${width}-font$scale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                    }
                    d.dismiss()
                } }
            }
        }
    }
    @Test fun systemThemeAndUnknownProgress() {
        ThemeSettings.save(app, ThemeMode.SYSTEM)
        for (night in listOf("night", "notnight")) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-$night-mdpi")
            withActivity { a ->
                val d = AppUpdateDialog(a, {}, {}); d.submit(sample.copy(stage = AppUpdateStage.DOWNLOADING)); d.show(); idle()
                val root = d.findViewById<View>(android.R.id.content)
                assertTrue(walk(root).filterIsInstance<ProgressBar>().single().isIndeterminate)
                assertFalse(walk(root).filterIsInstance<TextView>().any { it.text.contains("%") }); d.dismiss()
            }
        }
    }
}
