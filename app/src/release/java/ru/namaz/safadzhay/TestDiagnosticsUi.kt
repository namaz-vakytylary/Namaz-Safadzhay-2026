package ru.namaz.safadzhay

import android.widget.LinearLayout

/** No diagnostic screens, labels or readers in the production source set. */
internal object TestDiagnosticsUi {
    const val available = false
    fun addSettings(host: TestDiagnosticsHost, root: LinearLayout) = Unit
    fun show(host: TestDiagnosticsHost, route: String) = Unit
}
