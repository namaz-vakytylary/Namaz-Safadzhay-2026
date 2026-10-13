package ru.namaz.safadzhay

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*

/** Approved modern B, original logo and palette, content-driven height with a scroll cap. */
internal class AppUpdateDialog(
    private val activity: Activity,
    private val primaryAction: () -> Unit,
    private val continueAction: () -> Unit,
    private val preview: Boolean = false
) : Dialog(activity, if (ThemeSettings.isDark(activity)) R.style.AppDialogTheme else R.style.AppDialogThemeLight) {
    private val colors = ThemeSettings.colors(activity)
    private var scroll: ScrollView? = null
    private var snapshot: StoreAppUpdate? = null
    private var percentText: TextView? = null
    private var progressView: ProgressBar? = null
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, radius: Int, outline: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); outline?.let { setStroke(dp(1), it) }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = true
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.add(view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun action(title: String, primary: Boolean, outline: Boolean = false, click: () -> Unit) =
        text(title, 16f, if (primary) colors.onAction else if (outline) colors.primary else colors.secondaryText, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(if (primary) colors.primary else colors.surface, 12, if (outline) colors.primary else null)
            isFocusable = true; setOnClickListener { click() }
        }
    init { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private fun updateProgress(value: StoreAppUpdate) {
        val percent = if (value.stage == AppUpdateStage.DOWNLOADING) value.percent else null
        val label = percent?.let { "Загружено $it%" } ?: "Ожидаем данные о загрузке…"
        if (percentText?.text != label) percentText?.text = label
        progressView?.apply {
            isIndeterminate = percent == null; if (percent != null) progress = percent
            contentDescription = percent?.let { "Загружено $it процентов" } ?: "Загрузка через RuStore"
        }
    }
    fun submit(value: StoreAppUpdate) {
        // Byte updates keep the existing views, keyboard focus and scroll position.
        if (snapshot?.copy(downloadedBytes = value.downloadedBytes, totalBytes = value.totalBytes) == value &&
            value.stage in listOf(AppUpdateStage.DOWNLOADING, AppUpdateStage.WAITING)) {
            snapshot = value; updateProgress(value); return
        }
        snapshot = value; percentText = null; progressView = null
        val scrollY = scroll?.scrollY ?: 0
        val root = column().apply {
            tag = "app_update_card"; setPadding(dp(18), dp(18), dp(18), dp(18)); background = shape(colors.surface, 18, colors.outline)
        }
        if (preview) root.add(text("TEST · пример состояния, обновление не запускается", 12f, colors.secondaryText))
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(activity).apply {
            tag = "app_update_logo"; setImageResource(R.drawable.ic_launcher_source); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val labels = column().apply { add(text("Namaz Vakytylary", 17f, colors.onSurface, true)); add(text(value.versionLabel, 14f, colors.secondaryText), 4) }
        heading.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.add(heading, if (preview) 10 else 0)
        root.add(text(when (value.stage) {
            AppUpdateStage.AVAILABLE -> "Доступно обновление"
            AppUpdateStage.WAITING -> "Подготовка загрузки"
            AppUpdateStage.DOWNLOADING -> "Загружаем обновление"
            AppUpdateStage.READY -> "Готово к установке"
            AppUpdateStage.INSTALLING -> "Установка через RuStore"
            AppUpdateStage.FAILED -> "Обновление не завершено"
        }, 21f, colors.onSurface, true), 14)
        root.add(text(when (value.stage) {
            AppUpdateStage.AVAILABLE -> "Новая версия доступна в RuStore."
            AppUpdateStage.WAITING -> "Подготавливаем обновление через RuStore…"
            AppUpdateStage.DOWNLOADING -> "Обновление скачивается через RuStore."
            AppUpdateStage.READY -> "Загрузка завершена. RuStore установит обновление и перезапустит приложение."
            AppUpdateStage.INSTALLING -> "Следуйте инструкциям RuStore. Установка может закрыть или перезапустить приложение."
            AppUpdateStage.FAILED -> "Можно попробовать ещё раз или продолжить пользоваться приложением."
        }, 14f, colors.secondaryText), 6)
        if (value.stage == AppUpdateStage.AVAILABLE) root.add(column().apply {
            tag = "app_update_changes"; setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(colors.noticeSurface, 12)
            add(text("Что нового", 14f, colors.onSurface, true))
            add(text(value.whatsNew?.trim()?.takeIf { it.isNotEmpty() } ?: "Описание изменений пока недоступно.", 14f, colors.secondaryText), 6)
        }, 14)
        if (value.stage in listOf(AppUpdateStage.WAITING, AppUpdateStage.DOWNLOADING)) {
            percentText = text("", 14f, colors.secondaryText).apply { tag = "app_update_percent"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            root.add(percentText!!, 16)
            progressView = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "app_update_progress"; max = 100
                progressTintList = ColorStateList.valueOf(colors.primary); progressBackgroundTintList = ColorStateList.valueOf(colors.progressTrack)
                indeterminateTintList = ColorStateList.valueOf(colors.primary)
            }
            root.add(progressView!!, 6); updateProgress(value)
        }
        when (value.stage) {
            AppUpdateStage.AVAILABLE, AppUpdateStage.FAILED, AppUpdateStage.READY -> root.add(action(
                when (value.stage) { AppUpdateStage.READY -> "Установить"; AppUpdateStage.FAILED -> "Повторить"; else -> "Обновить" }, true
            ) { primaryAction() }.apply { tag = "app_update_primary" }, 16)
            else -> Unit
        }
        val continueDownload = value.stage in listOf(AppUpdateStage.WAITING, AppUpdateStage.DOWNLOADING, AppUpdateStage.INSTALLING)
        root.add(action(if (continueDownload) "Продолжить" else "Позже", false, outline = continueDownload) {
            continueAction(); dismiss()
        }.apply { tag = "app_update_continue" }, 8)
        scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (activity.resources.displayMetrics.heightPixels * .85).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(root); clipToOutline = true; background = shape(colors.surface, 18, colors.outline) }
        setContentView(scroll!!); window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        scroll!!.post { scroll?.scrollTo(0, scrollY) }
        if (isShowing) sizeWindow()
    }
    private fun sizeWindow() {
        window?.setLayout(minOf(dp(360), activity.resources.displayMetrics.widthPixels - dp(36)), ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setDimAmount(.42f)
    }
    override fun show() {
        super.show(); sizeWindow()
        val root = window?.decorView ?: return
        if (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
            root.alpha = 0f; root.animate().alpha(1f).setDuration(200L).start()
        }
    }
}
