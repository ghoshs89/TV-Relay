package dev.relay.tv.ui

import androidx.compose.ui.unit.sp

/**
 * Text sizes used across the app. Nothing is smaller than [label]: on a TV viewed from a sofa,
 * 9 to 11 sp text can't be read.
 */
object Type {
    /** Badges, times, secondary lines. The smallest size used anywhere. */
    val label = 12.sp
    val caption = 13.sp
    val small = 14.sp
    val body = 15.sp
    val bodyLarge = 16.sp
    val title = 20.sp
    val heading = 22.sp
}
