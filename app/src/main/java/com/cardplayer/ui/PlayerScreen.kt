package com.cardplayer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardplayer.MainViewModel
import com.cardplayer.NfcState
import com.cardplayer.Notice
import com.cardplayer.R
import com.cardplayer.data.SourceType
import com.cardplayer.link.YouTubeChapters
import com.cardplayer.playback.NowPlaying
import com.cardplayer.ui.settings.PasswordPromptDialog
import com.cardplayer.ui.settings.SettingsLock
import com.cardplayer.ui.theme.Berry
import com.cardplayer.ui.theme.Ink
import com.cardplayer.ui.theme.NightText
import com.cardplayer.ui.theme.NightTextSoft
import com.cardplayer.ui.theme.Teal
import com.cardplayer.util.Bedtime
import com.cardplayer.util.SystemSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong

/** Where on the screen a tap landed. */
enum class Zone { CENTER, TOP, BOTTOM, LEFT, RIGHT }

/** [art] = where the picture is drawn (null until laid out): touching it is the centre, wherever the layout put it. */
private fun zoneAt(pos: Offset, size: IntSize, art: Rect?): Zone {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dx = pos.x - cx
    val dy = pos.y - cy
    if (art != null) {
        if (art.contains(pos)) return Zone.CENTER
    } else if (hypot(dx, dy) < min(size.width, size.height) * 0.32f) return Zone.CENTER
    val nx = dx / size.width
    val ny = dy / size.height
    return if (abs(nx) > abs(ny)) {
        if (dx < 0) Zone.LEFT else Zone.RIGHT
    } else {
        if (dy < 0) Zone.TOP else Zone.BOTTOM
    }
}

/**
 * Grip guard: what separates a deliberate tap from a hand that is just holding the phone.
 * [edge] = dead strip round the screen (fingers wrapped round the sides land there),
 * [maxTapMs] = a finger left down longer than this is resting, not tapping,
 * [holdMs] = how long the picture is held to stop, [skipCooldownMs] = minimum gap between two skips,
 * [scrubEngage] = how far a finger slides sideways before it starts scrubbing,
 * [scrubSettle] = a scrubbing finger must be slower than this (per second) as it lifts, or it was a flick and nothing happens.
 */
private data class GripGuard(
    val edge: Dp,
    val maxTapMs: Long,
    val holdMs: Long,
    val skipCooldownMs: Long,
    val scrubEngage: Dp,
    val scrubSettle: Dp
)

private fun gripGuard(level: String) = when (level) {
    "off" -> GripGuard(edge = 0.dp, maxTapMs = 1000, holdMs = 800, skipCooldownMs = 0, scrubEngage = 24.dp, scrubSettle = Dp.Infinity)
    "strong" -> GripGuard(edge = 56.dp, maxTapMs = 350, holdMs = 2500, skipCooldownMs = 1200, scrubEngage = 64.dp, scrubSettle = 250.dp)
    else -> GripGuard(edge = 28.dp, maxTapMs = 500, holdMs = 1500, skipCooldownMs = 600, scrubEngage = 40.dp, scrubSettle = 500.dp)
}

private class Touch(val start: Offset, val downAt: Long) {
    /** Where the finger is now. */
    var at = start
    /** Slid, or landed together with another finger: not a tap any more. */
    var spoiled = false
    /** Landed together with another finger, or set off up / down: never a scrub. */
    var noScrub = false
}

/** Drag-to-scrub speed: the finger slides up for faster, down for finer, after the scrub has begun. */
enum class ScrubGear { FINE, NORMAL, FAST }

/** Each gear sits this far above / below where the scrub began. */
private val ScrubGearBand = 56.dp

/** Leaving a gear takes a little further than entering it, so a wobbly finger doesn't flicker between two. */
private fun scrubGear(dy: Float, current: ScrubGear, band: Float): ScrubGear {
    val keep = band * 0.7f
    return when {
        dy < -band || (current == ScrubGear.FAST && dy < -keep) -> ScrubGear.FAST
        dy > band || (current == ScrubGear.FINE && dy > keep) -> ScrubGear.FINE
        else -> ScrubGear.NORMAL
    }
}

/** What the player screen does with a scrub. Moves are fractions of the screen width, right = forward. */
private class ScrubCallbacks(
    val onStart: () -> Unit,
    val onMove: (widthFraction: Float, gear: ScrubGear) -> Unit,
    /** [commit] false = dropped (a flick, another finger joined or slid): nothing changes. */
    val onEnd: (commit: Boolean) -> Unit
)

/** The one finger scrubbing in a gesture, and where every other finger was when it began. */
private class Scrubbing(val id: PointerId, var lastX: Float, val originY: Float, val others: Map<PointerId, Offset>) {
    var gear = ScrubGear.NORMAL
    val velocity = VelocityTracker()
}

/**
 * Toddler taps, judged one finger at a time so a resting thumb never blocks a real tap:
 * a finger that lands in the edge strip is ignored for good; a tap must lift within [GripGuard.maxTapMs]
 * without sliding; fingers that land together (a palm, a grab) cancel each other; holding the sides
 * does nothing; holding the picture alone for [GripGuard.holdMs] stops.
 *
 * Scrubbing (when [scrub] is set) is harder to hit by accident: one finger that landed on its own slides
 * [GripGuard.scrubEngage] sideways, mostly flat; only one scrub per touch; another finger landing or sliding
 * drops it; and it only jumps if the finger has come to rest when it lifts (a swipe or flick does nothing).
 */
private suspend fun PointerInputScope.detectToddlerTaps(
    guard: GripGuard,
    pictureOnly: Boolean,
    onZone: (Zone) -> Unit,
    onCenterHold: () -> Unit,
    /** A lone clean finger has landed on the picture: a stop is coming if it stays. */
    onCenterHoldStart: () -> Unit,
    /** That finger lifted, slid, got company, or the stop happened. */
    onCenterHoldEnd: () -> Unit,
    /** Drag-to-scrub; null = off. */
    scrub: ScrubCallbacks?,
    /** The picture's bounds in this detector's coordinates, null until laid out. */
    artBounds: () -> Rect?
) {
    val edgePx = guard.edge.toPx()
    val slop = viewConfiguration.touchSlop
    val engagePx = guard.scrubEngage.toPx()
    val settlePx = guard.scrubSettle.toPx()
    val gearBandPx = ScrubGearBand.toPx()
    var lastSkipAt = 0L
    fun inGrip(p: Offset) = p.x < edgePx || p.y < edgePx || p.x > size.width - edgePx || p.y > size.height - edgePx

    awaitEachGesture {
        val touches = mutableMapOf<PointerId, Touch>()
        var holding: Touch? = null
        var scrubbing: Scrubbing? = null
        // One scrub per touch: once it ends or is dropped, the rest of this touch can't start another.
        var scrubUsed = false
        fun endScrub(commit: Boolean) {
            scrubbing = null
            scrub?.onEnd(commit)
        }
        try {
            while (true) {
                // Wake up for a hold-to-stop only while exactly one clean finger sits on the picture.
                val holder = touches.values.singleOrNull()?.takeIf { !it.spoiled && zoneAt(it.start, size, artBounds()) == Zone.CENTER }
                if (holder !== holding) {
                    if (holding != null) onCenterHoldEnd()
                    if (holder != null) onCenterHoldStart()
                    holding = holder
                }
                val event = if (holder == null) awaitPointerEvent() else {
                    val wait = holder.downAt + guard.holdMs - SystemClock.uptimeMillis()
                    withTimeoutOrNull(wait.coerceAtLeast(1)) { awaitPointerEvent() }
                }
                if (event == null) {
                    holder!!.spoiled = true
                    onCenterHold()
                    continue
                }
                for (change in event.changes) {
                    if (change.changedToDownIgnoreConsumed()) {
                        if (inGrip(change.position)) continue
                        val touch = Touch(change.position, change.uptimeMillis)
                        val landedTogether = touches.values.filter { change.uptimeMillis - it.downAt < guard.maxTapMs }
                        if (landedTogether.isNotEmpty()) {
                            landedTogether.forEach { it.spoiled = true; it.noScrub = true }
                            touch.spoiled = true
                            touch.noScrub = true
                        }
                        // A finger joining a scrub (a grab, a second hand) drops the scrub and is no tap either.
                        if (scrubbing != null) {
                            endScrub(commit = false)
                            touch.spoiled = true
                        }
                        touches[change.id] = touch
                        continue
                    }
                    val touch = touches[change.id] ?: continue
                    touch.at = change.position
                    val moved = change.position - touch.start
                    if (moved.getDistance() > slop) touch.spoiled = true

                    val s = scrubbing
                    if (s != null && s.id == change.id) {
                        s.velocity.addPosition(change.uptimeMillis, change.position)
                        if (change.changedToUpIgnoreConsumed()) {
                            touches.remove(change.id)
                            val v = s.velocity.calculateVelocity()
                            endScrub(commit = hypot(v.x, v.y) <= settlePx)
                            continue
                        }
                        val gear = scrubGear(change.position.y - s.originY, s.gear, gearBandPx)
                        val dx = change.position.x - s.lastX
                        s.lastX = change.position.x
                        if (dx != 0f || gear != s.gear) {
                            s.gear = gear
                            scrub?.onMove(dx / size.width, gear)
                        }
                        continue
                    }
                    if (s != null) {
                        // Another finger that was resting starts to slide (a palm, the other hand): drop the scrub.
                        val anchor = s.others[change.id]
                        if (anchor != null && (change.position - anchor).getDistance() > slop) endScrub(commit = false)
                    } else if (scrub != null && !scrubUsed && !touch.noScrub && change.pressed) {
                        if (abs(moved.x) >= engagePx) {
                            if (abs(moved.y) * 2 <= abs(moved.x)) {
                                scrubUsed = true
                                scrubbing = Scrubbing(
                                    id = change.id,
                                    lastX = change.position.x,
                                    originY = change.position.y,
                                    others = touches.filterKeys { it != change.id }.mapValues { it.value.at }
                                ).also { it.velocity.addPosition(change.uptimeMillis, change.position) }
                                scrub.onStart()
                            } else {
                                touch.noScrub = true
                            }
                        } else if (abs(moved.y) >= engagePx) {
                            touch.noScrub = true
                        }
                    }

                    if (change.changedToUpIgnoreConsumed()) {
                        touches.remove(change.id)
                        // Consumed = a button inside the screen (e.g. "Latest episode") took it.
                        if (touch.spoiled || change.isConsumed || change.uptimeMillis - touch.downAt > guard.maxTapMs) continue
                        val zone = zoneAt(touch.start, size, artBounds())
                        if (pictureOnly && zone != Zone.CENTER) continue
                        if (zone == Zone.LEFT || zone == Zone.RIGHT) {
                            if (change.uptimeMillis - lastSkipAt < guard.skipCooldownMs) continue
                            lastSkipAt = change.uptimeMillis
                        }
                        onZone(zone)
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            if (holding != null) onCenterHoldEnd()
            // The detector was torn down mid-scrub (screen changed): drop it rather than leave the read-out up.
            if (scrubbing != null) endScrub(commit = false)
        }
    }
}

/**
 * The toddler-facing screen. One big picture in the middle, and the whole screen is the
 * controller: centre tap = play/pause, centre hold = stop, top/bottom = volume, left/right = track.
 */
@Composable
fun PlayerScreen(viewModel: MainViewModel) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val unknownCard by viewModel.unknownCard.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nfc by viewModel.nfc.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val noCards = cards.isEmpty()
    val haptic = LocalHapticFeedback.current
    val battery = rememberBattery()
    var askPassword by remember { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val volume by viewModel.player.volume.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.player.sleepEndsAt.collectAsStateWithLifecycle()
    val sleepAtTrackEnd by viewModel.player.sleepAtTrackEnd.collectAsStateWithLifecycle()

    // Ticks every 10 s: drives the clock, the sleep countdown and the bedtime check.
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNow = System.currentTimeMillis()
            // Every 10 s, but never across a minute boundary: the clock must flip right on the minute.
            delay(minOf(10_000L, 60_000L - clockNow % 60_000L + 20L))
        }
    }
    val nightLight = remember(settings, clockNow) { Bedtime.nightLightActive(settings) }
    val sleepLabel: String? = when {
        sleepAtTrackEnd -> stringResource(R.string.player_sleep_after_song)
        sleepEndsAt != null -> {
            val mins = ((sleepEndsAt!! - clockNow) / 60_000L).coerceAtLeast(0)
            if (mins >= 1) stringResource(R.string.player_sleep_minutes, mins) else stringResource(R.string.player_sleep_soon)
        }
        else -> null
    }

    // Full-screen tint on every zone tap (warm = louder, cool = quieter, teal = skip, white = play/pause).
    val flash = remember { Animatable(0f) }
    var flashColor by remember { mutableStateOf(Color.White) }

    var message by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(Unit) {
        viewModel.notices.collect {
            message = it
            delay(4000)
            message = null
        }
    }

    var activeZone by remember { mutableStateOf<Zone?>(null) }
    var zoneTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(zoneTick) {
        if (zoneTick > 0) {
            if (settings.zoneFlash) {
                flashColor = when (activeZone) {
                    Zone.TOP -> Color(0xFFFF9A3C)
                    Zone.BOTTOM -> Color(0xFF4F9DFF)
                    Zone.LEFT, Zone.RIGHT -> Color(0xFF2EC4B6)
                    else -> Color.White
                }
                flash.snapTo(0.35f)
                flash.animateTo(0f, tween(450))
            } else {
                delay(350)
            }
            activeZone = null
        }
    }
    var volumeTick by remember { mutableIntStateOf(0) }
    var volumeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(volumeTick) {
        if (volumeTick > 0) {
            volumeVisible = true
            delay(1400)
            volumeVisible = false
        }
    }
    // The phone's volume keys go through the limit too (MainActivity): show the bars for those as well.
    var seenVolume by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(volume) {
        if (seenVolume != null && seenVolume != volume) volumeTick++
        seenVolume = volume
    }

    // Toddler-proof: the back button never leaves the app. It only returns to the home screen when
    // nothing holds the music there (a "keep playing" card, or a card already lifted off).
    BackHandler { viewModel.playerBack() }

    // Night light: dim, warm screen with light text, regardless of the app theme.
    val base = MaterialTheme.colorScheme
    val scheme = if (nightLight) {
        val bg = lerp(Color.Black, Color(Bedtime.nightLightColor(settings)), Bedtime.nightLightBrightness(settings).coerceIn(5, 100) / 100f)
        base.copy(
            background = bg,
            surface = bg,
            onBackground = NightText.copy(alpha = 0.85f),
            onSurface = NightText.copy(alpha = 0.85f),
            onSurfaceVariant = NightTextSoft,
            surfaceVariant = Color.White.copy(alpha = 0.12f)
        )
    } else base

    MaterialTheme(colorScheme = scheme) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val current = nowPlaying
        if (current == null) {
            IdleContent(
                busy = busy,
                pixel = settings.pixelArt,
                clock = if (settings.showClock) clockText(clockNow) else null,
                nightLight = nightLight,
                noCards = noCards,
                gearTaps = settings.gearTaps
            )
        } else {
            NowPlayingContent(
                np = current,
                pixel = settings.pixelArt,
                dim = nightLight,
                activeZone = activeZone,
                volume = volume,
                volumeVisible = volumeVisible,
                guard = gripGuard(settings.gripGuard),
                pictureOnly = settings.pictureOnly,
                scrubEnabled = settings.dragToScrub && !settings.pictureOnly,
                haptics = settings.zoneHaptics,
                onSeek = { viewModel.player.seekInTrack(it) },
                onZone = { zone ->
                    activeZone = zone
                    zoneTick++
                    if (settings.zoneHaptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    when (zone) {
                        Zone.CENTER -> viewModel.player.togglePlayPause()
                        Zone.TOP -> {
                            viewModel.player.volumeUp()
                            volumeTick++
                        }
                        Zone.BOTTOM -> {
                            viewModel.player.volumeDown()
                            volumeTick++
                        }
                        Zone.LEFT -> viewModel.player.previous()
                        Zone.RIGHT -> viewModel.player.next()
                    }
                },
                onCenterHold = {
                    if (settings.zoneHaptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.player.stop()
                },
                onLatest = { viewModel.player.jumpToLatest() }
            )
        }

        // Zone flash overlay.
        if (flash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(flashColor.copy(alpha = flash.value))
            )
        }

        // Top-left: the sleep timer moon, and the battery (always, never, or only when it needs a grown-up's eye).
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sleepLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            battery?.takeIf {
                when (settings.showBattery) {
                    "always" -> true
                    "off" -> false
                    else -> it.charging || it.level <= 20
                }
            }?.let { BatteryGlyph(it) }
        }

        // Grown-up door: tiny faded gear, double tap only. Plain to see until the first card exists.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(60.dp)
                .alpha(if (noCards) 0.9f else 0.3f)
                .pointerInput(settings.gearTaps, settings.settingsPasswordHash, settings.zoneHaptics) {
                    val open = {
                        if (settings.zoneHaptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (SettingsLock.isLocked(settings)) askPassword = true else viewModel.openSettings()
                    }
                    if (settings.gearTaps == 1) detectTapGestures(onTap = { open() })
                    else detectTapGestures(onDoubleTap = { open() })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(if (settings.gearTaps == 1) R.string.player_gear_tap else R.string.player_gear_double_tap),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp)
            )
        }

        if (askPassword) {
            PasswordPromptDialog(
                settings = settings,
                onUnlock = { askPassword = false; viewModel.openSettings() },
                onDismiss = { askPassword = false }
            )
        }

        // Bottom edge: flashed notices (red = something went wrong, teal = good news) and the NFC warning.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            message?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (msg.error) Berry else Teal
                ) {
                    Text(
                        text = msg.text,
                        color = if (msg.error) Color.White else Ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
            if (nfc != NfcState.ON) NfcBanner(state = nfc, gearTaps = settings.gearTaps)
        }

        unknownCard?.let { uid ->
            UnknownCardOverlay(
                uid = uid,
                gate = settings.gateUnknownCardSetup,
                onSetUp = { viewModel.openEditor(uid) },
                onDismiss = { viewModel.dismissUnknownCard() }
            )
        }
    }
    }
}

private fun clockText(now: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

/**
 * "NFC is off": without it no card does anything, so say so. Opening the phone's NFC switch is
 * gated like the gear (single tap by default, double tap if set) so a toddler can't wander off into system settings.
 */
@Composable
private fun NfcBanner(state: NfcState, gearTaps: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val canFix = state == NfcState.OFF
    Surface(
        modifier = modifier.then(
            if (canFix) Modifier.pointerInput(gearTaps) {
                if (gearTaps == 1) detectTapGestures(onTap = { SystemSettings.openNfc(context) })
                else detectTapGestures(onDoubleTap = { SystemSettings.openNfc(context) })
            } else Modifier
        ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text = stringResource(
                when {
                    state == NfcState.MISSING -> R.string.player_nfc_missing
                    gearTaps == 1 -> R.string.player_nfc_off_tap
                    else -> R.string.player_nfc_off_double_tap
                }
            ),
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

private data class Battery(val level: Int, val charging: Boolean)

/** The phone's battery, live. Sticky broadcast, so the first value arrives at once. */
@Composable
private fun rememberBattery(): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) battery = Battery(level * 100 / scale, charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        read(ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED))
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }
    return battery
}

/** A small battery: red when low, a bolt inside while charging. */
@Composable
private fun BatteryGlyph(battery: Battery) {
    val low = battery.level <= 20 && !battery.charging
    val tint = if (low) Berry else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
            val stroke = 1.5.dp.toPx()
            val bodyW = size.width - 3.dp.toPx()
            drawRoundRect(
                color = tint,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyW - stroke, size.height - stroke),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(stroke)
            )
            // The little terminal on the right.
            drawRect(tint, topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f))
            if (battery.charging) {
                val bolt = Path().apply {
                    moveTo(bodyW * 0.58f, size.height * 0.1f)
                    lineTo(bodyW * 0.36f, size.height * 0.56f)
                    lineTo(bodyW * 0.5f, size.height * 0.56f)
                    lineTo(bodyW * 0.42f, size.height * 0.92f)
                    lineTo(bodyW * 0.66f, size.height * 0.44f)
                    lineTo(bodyW * 0.52f, size.height * 0.44f)
                    close()
                }
                drawPath(bolt, tint)
            } else {
                val inner = stroke + 1.5.dp.toPx()
                drawRect(
                    color = tint,
                    topLeft = Offset(inner, inner),
                    size = Size(((bodyW - 2 * inner) * battery.level / 100f).coerceAtLeast(1f), size.height - 2 * inner)
                )
            }
        }
        Text("${battery.level}%", style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun IdleContent(busy: Boolean, pixel: Boolean, clock: String?, nightLight: Boolean, noCards: Boolean, gearTaps: Int) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val logoSize = (minOf(maxWidth, maxHeight) * 0.5f).coerceIn(150.dp, 240.dp)

        val logo: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(12.dp, RoundedCornerShape(40.dp))
            ) {
                // Home screen: always the BoopBox logo, whether nothing played yet or a card just stopped.
                Image(
                    painter = painterResource(R.drawable.boopbox_logo),
                    contentDescription = "BoopBox",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(40.dp)),
                    contentScale = ContentScale.Crop
                )
                // Night light dims the chrome through the colour scheme; the logo needs its own shade.
                if (nightLight) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(40.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                }
            }
        }
        val caption: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(
                        when {
                            nightLight -> R.string.player_idle_night
                            noCards -> R.string.player_idle_no_cards
                            else -> R.string.player_idle_tap_card
                        }
                    ),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = if (landscape) 36.sp else 48.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                // First run: the only way in is the gear, and nobody knows that yet.
                if (noCards && !nightLight) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(if (gearTaps == 1) R.string.player_idle_hint_tap else R.string.player_idle_hint_double_tap),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 360.dp)
                    )
                }
            }
        }
        val clockFace: @Composable () -> Unit = {
            if (clock != null) {
                Text(
                    text = clock,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = if (landscape) 96.sp else 64.sp, fontWeight = FontWeight.Light),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (nightLight) 0.5f else 0.35f)
                )
            }
        }
        val spinner: @Composable () -> Unit = {
            if (busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        if (landscape) {
            // Logo + caption on the left, the clock big on its own on the right.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    logo()
                    Spacer(Modifier.height(18.dp))
                    caption()
                    spinner()
                }
                if (clock != null) {
                    Spacer(Modifier.width(64.dp))
                    clockFace()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                logo()
                Spacer(Modifier.height(36.dp))
                caption()
                if (clock != null) {
                    Spacer(Modifier.height(10.dp))
                    clockFace()
                }
                spinner()
            }
        }
    }
}

@Composable
private fun NowPlayingContent(
    np: NowPlaying,
    pixel: Boolean,
    /** Night light: shade the picture too, not only the chrome. */
    dim: Boolean,
    activeZone: Zone?,
    volume: Pair<Int, Int>,
    volumeVisible: Boolean,
    guard: GripGuard,
    pictureOnly: Boolean,
    scrubEnabled: Boolean,
    haptics: Boolean,
    onSeek: (Long) -> Unit,
    onZone: (Zone) -> Unit,
    onCenterHold: () -> Unit,
    onLatest: () -> Unit
) {
    val currentOnZone by rememberUpdatedState(onZone)
    val currentOnCenterHold by rememberUpdatedState(onCenterHold)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentNp by rememberUpdatedState(np)
    val currentHaptics by rememberUpdatedState(haptics)
    val haptic = LocalHapticFeedback.current
    // Hold-to-stop feedback: a ring fills round the picture while the finger stays on it.
    val scope = rememberCoroutineScope()
    val holdProgress = remember { Animatable(0f) }
    // Drag-to-scrub: where the song jumps to when the finger lifts (null = not scrubbing). Nothing is
    // heard until then, so a scrub that gets dropped leaves the song exactly as it was.
    var scrubTarget by remember { mutableStateOf<Double?>(null) }
    var scrubFrom by remember { mutableLongStateOf(0L) }
    var scrubTrack by remember { mutableStateOf("") }
    var scrubGear by remember { mutableStateOf(ScrubGear.NORMAL) }
    // Where the screen and the picture sit (in root coordinates), so a touch on the picture is play/pause
    // even in landscape, where it sits left of the middle. Only the touch detector reads these.
    var screenInRoot by remember { mutableStateOf(Offset.Zero) }
    var artInRoot by remember { mutableStateOf<Rect?>(null) }
    val artReach = with(LocalDensity.current) { 12.dp.toPx() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenInRoot = it.positionInRoot() }
            .pointerInput(guard, pictureOnly, scrubEnabled) {
                fun buzz(type: HapticFeedbackType) {
                    if (currentHaptics) haptic.performHapticFeedback(type)
                }
                val scrub = if (!scrubEnabled) null else ScrubCallbacks(
                    onStart = {
                        // Streams and apps that don't report a length can't be scrubbed.
                        val now = currentNp
                        if (now.durationMs > 0) {
                            scrubFrom = now.positionMs
                            scrubTarget = now.positionMs.toDouble()
                            scrubTrack = now.trackSeed
                            scrubGear = ScrubGear.NORMAL
                            buzz(HapticFeedbackType.GestureThresholdActivate)
                        }
                    },
                    onMove = { fraction, gear ->
                        scrubTarget?.let { target ->
                            if (gear != scrubGear) {
                                scrubGear = gear
                                buzz(HapticFeedbackType.SegmentTick)
                            }
                            // Stop a second short of the end so the jump doesn't land on "song over".
                            val duration = currentNp.durationMs
                            val last = (duration - 1000).coerceAtLeast(0L).toDouble()
                            scrubTarget = (target + fraction * scrubSpanMs(gear, duration)).coerceIn(0.0, last)
                        }
                    },
                    onEnd = { commit ->
                        scrubTarget?.let { target ->
                            scrubTarget = null
                            val ms = target.roundToLong()
                            when {
                                !commit -> buzz(HapticFeedbackType.Reject)
                                // Slid back to where it started, or the song changed under the finger: leave it.
                                abs(ms - scrubFrom) < 1000 || currentNp.trackSeed != scrubTrack -> Unit
                                else -> {
                                    buzz(HapticFeedbackType.Confirm)
                                    currentOnSeek(ms)
                                }
                            }
                        }
                    }
                )
                detectToddlerTaps(
                    guard = guard,
                    pictureOnly = pictureOnly,
                    onZone = { currentOnZone(it) },
                    onCenterHold = { currentOnCenterHold() },
                    onCenterHoldStart = {
                        scope.launch {
                            holdProgress.snapTo(0f)
                            // A plain tap shows nothing: the ring only starts once the finger has clearly stayed.
                            val lead = 250
                            holdProgress.animateTo(
                                1f,
                                tween(durationMillis = (guard.holdMs - lead).toInt().coerceAtLeast(1), delayMillis = lead, easing = LinearEasing)
                            )
                        }
                    },
                    onCenterHoldEnd = { scope.launch { holdProgress.snapTo(0f) } },
                    scrub = scrub,
                    // A little extra round the edge so a near miss on the picture still counts.
                    artBounds = { artInRoot?.translate(-screenInRoot)?.inflate(artReach) }
                )
            }
    ) {
        val landscape = maxWidth > maxHeight
        val shortSide = minOf(maxWidth, maxHeight)
        // Picture size follows the shorter screen edge: big on phones, not silly on tablets.
        val artSize = (shortSide * (if (landscape) 0.62f else 0.64f)).coerceIn(150.dp, 300.dp)
        val edgePad = if (landscape) 10.dp else 28.dp
        val infoMaxWidth = maxWidth * 0.45f

        // Faint diagonal guides so the four outer zones read as areas.
        if (!pictureOnly) {
            val guideColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawLine(guideColor, Offset(0f, 0f), c, strokeWidth = 3f)
                drawLine(guideColor, Offset(size.width, 0f), c, strokeWidth = 3f)
                drawLine(guideColor, Offset(0f, size.height), c, strokeWidth = 3f)
                drawLine(guideColor, Offset(size.width, size.height), c, strokeWidth = 3f)
            }

            // The zones are physical (left = previous, right = next, see zoneAt): the glyphs must not
            // mirror when the phone is set to a right-to-left language. A Box of its own, composed under
            // an LTR direction, is what makes align / padding(start, end) resolve left-to-right.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize()) {
                    ZoneGlyph(
                        icon = ZoneIcon.LOUDER,
                        caption = "+",
                        active = activeZone == Zone.TOP,
                        compact = landscape,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = edgePad)
                    )
                    ZoneGlyph(
                        icon = ZoneIcon.QUIETER,
                        caption = "−",
                        active = activeZone == Zone.BOTTOM,
                        compact = landscape,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = edgePad)
                    )
                    ZoneGlyph(
                        icon = ZoneIcon.PREVIOUS,
                        caption = null,
                        active = activeZone == Zone.LEFT,
                        compact = landscape,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 18.dp)
                    )
                    ZoneGlyph(
                        icon = ZoneIcon.NEXT,
                        caption = null,
                        active = activeZone == Zone.RIGHT,
                        compact = landscape,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 18.dp)
                    )
                }
            }
        }

        val centerScale by animateFloatAsState(
            targetValue = if (activeZone == Zone.CENTER) 0.94f else 1f,
            animationSpec = tween(150),
            label = "centerScale"
        )

        if (landscape) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ArtBox(np = np, pixel = pixel, dim = dim, size = artSize, scale = centerScale, holdProgress = holdProgress.value, onPlaced = { artInRoot = it })
                Spacer(Modifier.width(32.dp))
                Column(
                    modifier = Modifier.widthIn(max = infoMaxWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    InfoStack(np = np, scrubMs = scrubTarget?.roundToLong(), volume = volume, volumeVisible = volumeVisible, compact = true, onLatest = onLatest)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtBox(np = np, pixel = pixel, dim = dim, size = artSize, scale = centerScale, holdProgress = holdProgress.value, onPlaced = { artInRoot = it })
                Spacer(Modifier.height(22.dp))
                InfoStack(np = np, scrubMs = scrubTarget?.roundToLong(), volume = volume, volumeVisible = volumeVisible, compact = false, onLatest = onLatest)
            }
        }

        scrubTarget?.let { target ->
            ScrubReadout(
                targetMs = target.roundToLong(),
                fromMs = scrubFrom,
                durationMs = np.durationMs,
                gear = scrubGear,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** How much of the song one drag across the whole screen covers, per gear. */
private fun scrubSpanMs(gear: ScrubGear, durationMs: Long): Long {
    // Normal: the whole song across the screen, like the progress bar, but never more than 10 minutes
    // so a long audiobook file stays steerable.
    val normal = durationMs.coerceIn(60_000L, 10 * 60_000L)
    return when (gear) {
        ScrubGear.FINE -> normal / 6
        ScrubGear.NORMAL -> normal
        ScrubGear.FAST -> maxOf(normal * 4, durationMs)
    }
}

/** While scrubbing: how far the song will jump, where to, and the speed ladder (slide up = faster, down = finer). */
@Composable
private fun ScrubReadout(targetMs: Long, fromMs: Long, durationMs: Long, gear: ScrubGear, modifier: Modifier = Modifier) {
    val delta = targetMs - fromMs
    // Times and the +/− jump read left to right in every language, like the drag itself.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Ink.copy(alpha = 0.8f))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = (if (delta < 0) "−" else "+") + YouTubeChapters.formatTime(abs(delta)),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = "${YouTubeChapters.formatTime(targetMs)} / ${YouTubeChapters.formatTime(durationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            // Top to bottom the way the finger moves: up for fast, down for fine.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((g, label) in listOf(
                    ScrubGear.FAST to R.string.player_scrub_fast,
                    ScrubGear.NORMAL to R.string.player_scrub_normal,
                    ScrubGear.FINE to R.string.player_scrub_fine
                )) {
                    val on = g == gear
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Ink else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) Teal else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/** The big picture with the play / pause state on top of it, and the hold-to-stop ring while a finger rests on it. */
@Composable
private fun ArtBox(np: NowPlaying, pixel: Boolean, dim: Boolean, size: Dp, scale: Float, holdProgress: Float, onPlaced: (Rect) -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(size)
            // Before the press-shrink, so the tap target doesn't wobble with it.
            .onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(16.dp, RoundedCornerShape(36.dp))
    ) {
        CardArtwork(
            artwork = np.artworkBytes ?: np.artworkBitmap ?: np.artworkUrl,
            seed = np.trackSeed,
            color = np.card.color,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 36.dp,
            pixel = pixel
        )
        // Night light: the chrome is dimmed by the colour scheme, the picture needs its own shade.
        if (dim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
            )
        }
        if (!np.isPlaying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Ink.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(size * 0.44f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.player_play),
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.29f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                PauseIcon(modifier = Modifier.size(width = 16.dp, height = 18.dp), color = Color.White)
            }
        }
        if (holdProgress > 0.01f) {
            val ring = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2 + 6.dp.toPx()
                val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                drawArc(
                    color = Ink.copy(alpha = 0.35f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke)
                )
                drawArc(
                    color = ring,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/** Title, status, track dots, progress (at the scrub target while scrubbing) and the volume bar. */
@Composable
private fun InfoStack(np: NowPlaying, scrubMs: Long?, volume: Pair<Int, Int>, volumeVisible: Boolean, compact: Boolean, onLatest: () -> Unit) {
    Text(
        text = np.title,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        maxLines = if (compact) 3 else 2,
        overflow = TextOverflow.Ellipsis
    )
    np.status?.let {
        Spacer(Modifier.height(4.dp))
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Podcast playing an older episode: one tap back to the newest.
    if (!np.isExternal && np.card.sourceType == SourceType.PODCAST && np.trackIndex > 0) {
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondary)
                .clickable(onClick = onLatest)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(stringResource(R.string.player_latest_episode), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }

    if (np.trackCount in 2..24) {
        Spacer(Modifier.height(12.dp))
        // Track dots, the progress bar and the volume bars always run left to right: "next" is the right-hand zone in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until np.trackCount) {
                    val isCurrent = i == np.trackIndex
                    Box(
                        Modifier
                            .size(if (isCurrent) 14.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                    )
                }
            }
        }
    } else if (np.trackCount > 24) {
        // Too many for dots (a long audiobook, a big folder): plain numbers instead.
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${np.trackIndex + 1} / ${np.trackCount}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (np.durationMs > 0) {
        Spacer(Modifier.height(14.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(
                progress = { ((scrubMs ?: np.positionMs).toFloat() / np.durationMs.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.9f else 0.7f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    val volumeAlpha by animateFloatAsState(if (volumeVisible) 1f else 0f, tween(250), label = "vol")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.alpha(volumeAlpha),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val (level, cap) = volume
            val steps = cap.coerceAtLeast(1)
            for (i in 1..steps) {
                Box(
                    Modifier
                        .width(if (steps > 15) 8.dp else 12.dp)
                        .height((10 + 22 * i / steps).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i <= level) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
                )
            }
        }
    }
}

private enum class ZoneIcon { LOUDER, QUIETER, PREVIOUS, NEXT }

@Composable
private fun ZoneGlyph(icon: ZoneIcon, caption: String?, active: Boolean, compact: Boolean = false, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (active) 1.35f else 1f, tween(150), label = "zone")
    val alpha by animateFloatAsState(if (active) 1f else 0.35f, tween(150), label = "zoneAlpha")
    val tint = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(if (compact) 34.dp else 46.dp)) { drawZoneIcon(icon, tint) }
        if (caption != null) {
            Text(text = caption, fontSize = if (compact) 24.sp else 34.sp, fontWeight = FontWeight.ExtraBold, color = tint)
        }
    }
}

/** Own vector glyphs: font emoji (🔊 ⏭) look different on every phone and go monochrome on some. */
private fun DrawScope.drawZoneIcon(icon: ZoneIcon, color: Color) {
    val w = size.width
    val h = size.height
    when (icon) {
        ZoneIcon.LOUDER, ZoneIcon.QUIETER -> {
            // A speaker: box plus cone, then one sound arc for quieter, two for louder.
            val body = Path().apply {
                moveTo(w * 0.06f, h * 0.38f)
                lineTo(w * 0.24f, h * 0.38f)
                lineTo(w * 0.46f, h * 0.18f)
                lineTo(w * 0.46f, h * 0.82f)
                lineTo(w * 0.24f, h * 0.62f)
                lineTo(w * 0.06f, h * 0.62f)
                close()
            }
            drawPath(body, color)
            val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
            val radii = if (icon == ZoneIcon.LOUDER) listOf(0.2f, 0.34f) else listOf(0.2f)
            for (r in radii) {
                val rr = w * r
                drawArc(
                    color = color,
                    startAngle = -42f,
                    sweepAngle = 84f,
                    useCenter = false,
                    topLeft = Offset(w * 0.5f - rr, h * 0.5f - rr),
                    size = Size(rr * 2, rr * 2),
                    style = stroke
                )
            }
        }
        ZoneIcon.PREVIOUS, ZoneIcon.NEXT -> {
            // Skip: a triangle pointing at a bar.
            val next = icon == ZoneIcon.NEXT
            val barX = if (next) w * 0.78f else w * 0.12f
            drawRect(color, topLeft = Offset(barX, h * 0.2f), size = Size(w * 0.1f, h * 0.6f))
            val tri = Path().apply {
                if (next) {
                    moveTo(w * 0.12f, h * 0.18f)
                    lineTo(w * 0.7f, h * 0.5f)
                    lineTo(w * 0.12f, h * 0.82f)
                } else {
                    moveTo(w * 0.88f, h * 0.18f)
                    lineTo(w * 0.3f, h * 0.5f)
                    lineTo(w * 0.88f, h * 0.82f)
                }
                close()
            }
            drawPath(tri, color)
        }
    }
}

@Composable
private fun UnknownCardOverlay(uid: String, gate: Boolean, onSetUp: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "✨", fontSize = 64.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.player_new_card),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 38.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.player_new_card_not_set_up),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.player_card_id, uid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(22.dp))
                val gesture = if (gate) {
                    Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onSetUp() }) }
                } else {
                    Modifier.clickable(onClick = onSetUp)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .then(gesture),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(if (gate) R.string.player_set_up_double_tap else R.string.player_set_up),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.common_not_now), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
