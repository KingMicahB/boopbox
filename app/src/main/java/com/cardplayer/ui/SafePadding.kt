package com.cardplayer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * The player hides the status and navigation bars, and so does everything shown over it (a card waiting to be
 * tapped, the card picker). Padding by the bars is then no padding at all, and whatever sits at that edge goes
 * under the camera cut-out. These keep to the safe drawing area instead: the bars when they show, the cut-out
 * always, whichever way the phone is turned.
 */

/** Clear of the cut-out and the bars along the top (and the sides, where the cut-out is when the phone lies down). */
@Composable
fun Modifier.safeTopPadding(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/** Clear of the cut-out and the bars along the bottom (and the sides). */
@Composable
fun Modifier.safeBottomPadding(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))

/** Clear of the cut-out and the bars at the sides. */
@Composable
fun Modifier.safeSidePadding(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/**
 * The bars and the cut-out, but not the keyboard: for the grown-up screens' Scaffolds and their top and bottom
 * bars. The bars show there, so in portrait they cover the camera already; lying down the camera is at a side,
 * where the bars alone leave nothing.
 */
val WindowInsets.Companion.barsAndCutout: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Clear of the bars and the cut-out along the bottom (and the sides): a bottom bar of a grown-up screen. */
@Composable
fun Modifier.barsBottomPadding(): Modifier = windowInsetsPadding(WindowInsets.barsAndCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
