package ru.namaz.safadzhay

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.LinearLayout

/** Reuses the existing settings renderer; production's implementation has no TEST UI. */
internal data class TestDiagnosticsHost(
    val activity: Activity,
    val repository: ScheduleRepository,
    val selection: CitySelectionSettings,
    val location: CityLocationController,
    val panel: (String, String, () -> Unit) -> Pair<Dialog, LinearLayout>,
    val row: (Int, String, String, () -> Unit) -> LinearLayout,
    val isCurrent: (View) -> Boolean,
    val open: (String) -> Unit,
    val settings: () -> Unit,
    val ramadan: () -> Unit
)
