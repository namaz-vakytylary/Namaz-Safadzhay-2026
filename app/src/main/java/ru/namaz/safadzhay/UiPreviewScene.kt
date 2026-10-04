package ru.namaz.safadzhay

import java.time.LocalDateTime

/** UI clock selection; never supplied to the notification scheduler or calendar repository. */
internal data class UiPreviewScene(val key: String, val title: String, val time: LocalDateTime?)
