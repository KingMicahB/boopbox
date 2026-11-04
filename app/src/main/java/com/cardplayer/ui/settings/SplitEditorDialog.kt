@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardplayer.ui.settings

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import com.cardplayer.R
import com.cardplayer.audio.SilenceScanner
import com.cardplayer.data.Chapter
import com.cardplayer.data.Clip
import com.cardplayer.link.YouTubeChapters
import com.cardplayer.ui.theme.Berry
import com.cardplayer.ui.theme.Orange
import com.cardplayer.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shortest part the start / end cut may leave. */
private const val MIN_CLIP_MS = 2000L
/** How much each end of the cut plays when it is checked. */
private const val EDGE_PREVIEW_MS = 4000L
/** One tap on an earlier / later arrow. */
private const val NUDGE_MS = 250L
/** A start / end this close to the very start / end of the file counts as no cut there. */
private const val EDGE_SNAP_MS = 500L

/**
 * "Split & trim": loudness waveform with orange start / end handles (play only part of the file), split lines
 * between songs (silence detection + cuts by hand), tap the waveform to listen there, name the songs.
 * A [SongSource.YouTube] video has no waveform: it is listened to through YouTube's player, cuts by hand only.
 * [allowSplit] false is the trim alone (one song of a playlist). [onDone] gets the songs (empty = not split)
 * and the start / end cut (null = whole file). Chapters keep counting from the start of the file.
 */
@Composable
fun SplitEditorDialog(
    source: SongSource,
    initialChapters: List<Chapter>,
    initialClip: Clip?,
    allowSplit: Boolean = true,
    onDone: (chapters: List<Chapter>, clip: Clip?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var timeline by remember { mutableStateOf<SilenceScanner.Timeline?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    /** YouTube's length from the watch page, for a video that won't play embedded. */
    var pageDurationMs by remember { mutableLongStateOf(0L) }

    var thresholdDb by remember { mutableFloatStateOf(-40f) }
    var minSilenceSec by remember { mutableFloatStateOf(1.0f) }
    var minSongSec by remember { mutableFloatStateOf(10f) }
    val canAutoSplit = allowSplit && source is SongSource.File
    var autoSplit by remember { mutableStateOf(canAutoSplit && initialChapters.isEmpty()) }
    /** Cuts the user added by hand (ms). Starts with the card's existing chapters. */
    var manualCuts by remember { mutableStateOf(initialChapters.drop(1).map { it.startMs }.toSet()) }
    /** Automatic cuts the user removed (ms). */
    var removedCuts by remember { mutableStateOf(setOf<Long>()) }
    var names by remember { mutableStateOf(initialChapters.associate { it.startMs to it.title }) }

    var clipStart by remember { mutableLongStateOf(initialClip?.startMs?.coerceAtLeast(0L) ?: 0L) }
    /** 0 until the length is known, then always a real time. */
    var clipEnd by remember { mutableLongStateOf(initialClip?.endMs?.coerceAtLeast(0L) ?: 0L) }
    /** Which handle the current drag moves: true = start, null = none. */
    var dragging by remember { mutableStateOf<Boolean?>(null) }

    var playheadMs by remember { mutableLongStateOf(0L) }
    var previewing by remember { mutableStateOf(false) }
    var previewUntil by remember { mutableLongStateOf(0L) }
    var previewToken by remember { mutableIntStateOf(0) }
    var checkingCuts by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preview: SongPreview = remember(source) {
        when (source) {
            is SongSource.File -> FileSongPreview(context, source.uri)
            is SongSource.YouTube -> YouTubeSongPreview(context, source.videoId)
        }
    }
    DisposableEffect(preview) { onDispose { preview.release() } }

    var analysing by remember { mutableStateOf(source is SongSource.File) }
    LaunchedEffect(source) {
        when (source) {
            is SongSource.File -> {
                try {
                    // Partial timelines arrive while decoding; the waveform fills in as they come.
                    SilenceScanner.analyze(context, Uri.parse(source.uri)) { partial ->
                        timeline = partial
                        progress = partial.progress
                    }
                } catch (e: Exception) {
                    error = context.getString(R.string.split_error_read, e.message ?: context.getString(R.string.split_error_unknown))
                }
                analysing = false
            }
            is SongSource.YouTube -> {
                pageDurationMs = YouTubeChapters.scan("https://www.youtube.com/watch?v=${source.videoId}")?.durationMs ?: 0L
            }
        }
    }

    val totalMs = maxOf(timeline?.durationMs ?: 0L, preview.durationMs, pageDurationMs)
    // The length arrives late: an end of 0 (= to the end) or past the end becomes the real end.
    LaunchedEffect(totalMs) {
        if (totalMs <= 0) return@LaunchedEffect
        if (clipEnd <= 0L || clipEnd > totalMs) clipEnd = totalMs
        clipStart = clipStart.coerceIn(0L, (clipEnd - MIN_CLIP_MS).coerceAtLeast(0L))
    }
    val endMs = if (clipEnd > 0L) clipEnd else totalMs

    // Follow the preview playhead and stop at its end.
    LaunchedEffect(previewToken) {
        if (previewToken == 0) return@LaunchedEffect
        delay(150) // let the player start before asking whether it plays
        while (previewing) {
            val pos = preview.positionMs()
            playheadMs = pos
            if (pos >= previewUntil || !preview.isPlaying()) break
            delay(40)
        }
        if (previewing) preview.pause()
        previewing = false
    }

    fun stopPreview() {
        checkingCuts = false
        previewing = false
        preview.pause()
    }

    fun previewAt(fromMs: Long, untilMs: Long) {
        val from = fromMs.coerceIn(0L, totalMs.coerceAtLeast(0L))
        playheadMs = from
        if (!preview.ready || untilMs <= from) return
        preview.play(from)
        previewUntil = untilMs
        previewing = true
        previewToken++
    }

    fun hearStart() = previewAt(clipStart, minOf(clipStart + EDGE_PREVIEW_MS, endMs))
    fun hearEnd() = previewAt(maxOf(endMs - EDGE_PREVIEW_MS, clipStart), endMs)
    fun setStart(ms: Long) {
        clipStart = ms.coerceIn(0L, (endMs - MIN_CLIP_MS).coerceAtLeast(0L))
    }
    fun setEnd(ms: Long) {
        clipEnd = ms.coerceIn((clipStart + MIN_CLIP_MS).coerceAtMost(totalMs), totalMs)
    }

    /** Plays 1.5 s before to 3 s after every cut, one after another: a quick audit of the split. */
    fun hearEveryCut(cuts: List<Long>) {
        if (cuts.isEmpty() || !preview.ready) return
        checkingCuts = true
        scope.launch {
            for (cut in cuts) {
                if (!checkingCuts) break
                previewAt((cut - 1500L).coerceAtLeast(0L), cut + 3000L)
                delay(4700L)
            }
            checkingCuts = false
        }
    }

    val options = SilenceScanner.Options(
        thresholdDb = thresholdDb,
        minSilenceMs = (minSilenceSec * 1000).toLong(),
        minSongMs = (minSongSec * 1000).toLong()
    )
    val tl = timeline
    val silences = remember(tl, thresholdDb, minSilenceSec, analysing) { tl?.let { SilenceScanner.findSilences(it, options) } ?: emptyList() }
    val autoCuts = remember(silences, autoSplit, removedCuts) {
        if (autoSplit) silences.map { SilenceScanner.cutPoint(it) }.filterNot { it in removedCuts }.toSet() else emptySet()
    }
    // Songs are worked out inside the start / end cut. The first keeps starting at 0 (its picture is filed
    // under that), the player begins it at the start cut.
    val chapters = remember(autoCuts, manualCuts, minSongSec, names, clipStart, endMs, allowSplit) {
        if (!allowSplit || endMs <= 0L) emptyList()
        else SilenceScanner.chaptersFrom(
            (autoCuts + manualCuts).map { it - clipStart },
            endMs - clipStart,
            options,
            names.mapKeys { it.key - clipStart }
        ).mapIndexed { i, ch ->
            if (i == 0) ch.copy(startMs = 0L, title = names[0L] ?: ch.title) else ch.copy(startMs = ch.startMs + clipStart)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(if (allowSplit) R.string.split_title else R.string.trim_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
              Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
              ) {
                Text(
                    stringResource(if (allowSplit) R.string.split_trim_hint else R.string.trim_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // YouTube's own player: it is how a video with no audio on the phone is listened to.
                if (preview is YouTubeSongPreview) {
                    preview.Video(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    if (preview.failed) {
                        Text(stringResource(R.string.trim_youtube_no_preview), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (analysing && error == null) {
                    Text(
                        if (tl == null) stringResource(R.string.split_reading) else stringResource(R.string.split_reading_progress, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                // Waveform: loudness, shaded silences, start / end handles, split lines, playhead.
                // Tap to listen there; drag a handle and let go to hear the cut. Time runs left to right in every language.
                val grabPx = with(LocalDensity.current) { 28.dp.toPx() }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Waveform(
                        timeline = tl,
                        silences = silences,
                        cuts = chapters.drop(1).map { it.startMs },
                        clipStart = clipStart,
                        clipEnd = endMs,
                        activeHandle = dragging,
                        playheadMs = playheadMs,
                        showPlayhead = previewing || playheadMs > 0,
                        totalMs = totalMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (source is SongSource.File) 120.dp else 72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(totalMs) {
                                detectTapGestures { pos ->
                                    if (totalMs > 0) {
                                        checkingCuts = false
                                        val at = (pos.x / size.width * totalMs).toLong().coerceIn(0, totalMs)
                                        previewAt(at, if (at < endMs) endMs else totalMs)
                                    }
                                }
                            }
                            .pointerInput(totalMs, allowSplit) {
                                fun msAt(x: Float) = (x / size.width * totalMs).toLong().coerceIn(0L, totalMs)
                                detectHorizontalDragGestures(
                                    onDragStart = { pos ->
                                        if (totalMs <= 0) return@detectHorizontalDragGestures
                                        val sx = size.width * clipStart.toFloat() / totalMs
                                        val ex = size.width * endMs.toFloat() / totalMs
                                        val nearStart = abs(pos.x - sx) <= abs(pos.x - ex)
                                        // Splitting: only a drag that starts on a handle moves it. Trim alone: any drag.
                                        if (!allowSplit || minOf(abs(pos.x - sx), abs(pos.x - ex)) <= grabPx) {
                                            stopPreview()
                                            dragging = nearStart
                                        }
                                    },
                                    onDragEnd = {
                                        when (dragging) {
                                            true -> hearStart()
                                            false -> hearEnd()
                                            null -> Unit
                                        }
                                        dragging = null
                                    },
                                    onDragCancel = { dragging = null },
                                    onHorizontalDrag = { change, _ ->
                                        val d = dragging ?: return@detectHorizontalDragGestures
                                        change.consume()
                                        if (d) setStart(msAt(change.position.x)) else setEnd(msAt(change.position.x))
                                    }
                                )
                            }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The clock reads left to right in every language.
                    val clock = ltr("${YouTubeChapters.formatTime(playheadMs)} / ${YouTubeChapters.formatTime(totalMs)}")
                    Text(
                        stringResource(if (previewing) R.string.split_time_playing else R.string.split_time_idle, clock),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (previewing) {
                        TextButton(onClick = { stopPreview() }) { Text(stringResource(R.string.split_stop)) }
                    }
                    if (allowSplit) {
                        OutlinedButton(
                            enabled = totalMs > 0 && playheadMs > clipStart && playheadMs < endMs,
                            onClick = {
                                manualCuts = manualCuts + playheadMs
                                removedCuts = removedCuts - playheadMs
                            }
                        ) { Text(stringResource(R.string.split_here)) }
                    }
                }

                // Start / end cut: a quarter second a tap, each tap plays that end of the cut again.
                Text(stringResource(R.string.trim_length, ltrTenths(endMs - clipStart), ltrTenths(totalMs)), style = MaterialTheme.typography.titleSmall)
                EdgeRow(
                    label = stringResource(R.string.trim_start, ltrTenths(clipStart)),
                    enabled = totalMs > 0,
                    onEarlier = { setStart(clipStart - NUDGE_MS); hearStart() },
                    onLater = { setStart(clipStart + NUDGE_MS); hearStart() },
                    onListen = { hearStart() }
                )
                EdgeRow(
                    label = stringResource(R.string.trim_end, ltrTenths(endMs)),
                    enabled = totalMs > 0,
                    onEarlier = { setEnd(endMs - NUDGE_MS); hearEnd() },
                    onLater = { setEnd(endMs + NUDGE_MS); hearEnd() },
                    onListen = { hearEnd() }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = preview.ready && totalMs > 0,
                        onClick = { if (previewing) stopPreview() else previewAt(clipStart, endMs) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(if (previewing) R.string.split_stop else R.string.trim_play)) }
                    OutlinedButton(
                        enabled = totalMs > 0 && (clipStart > 0 || endMs < totalMs),
                        onClick = {
                            stopPreview()
                            clipStart = 0L
                            clipEnd = totalMs
                        }
                    ) { Text(stringResource(R.string.trim_reset)) }
                }

                if (allowSplit) {
                    Spacer(Modifier.height(8.dp))
                    if (chapters.size > 1) {
                        OutlinedButton(
                            enabled = preview.ready,
                            onClick = {
                                if (checkingCuts) stopPreview() else hearEveryCut(chapters.drop(1).map { it.startMs })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(if (checkingCuts) R.string.split_stop_checking else R.string.split_hear_cuts))
                        }
                    }

                    if (canAutoSplit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = autoSplit, onCheckedChange = { autoSplit = it })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.split_auto), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (autoSplit) {
                            Text(stringResource(R.string.split_threshold, ltr("${thresholdDb.toInt()} dB")), style = MaterialTheme.typography.bodyMedium)
                            Slider(value = thresholdDb, onValueChange = { thresholdDb = it }, valueRange = -60f..-20f, steps = 7)
                            Text(stringResource(R.string.split_min_silence, "%.1f".format(minSilenceSec)), style = MaterialTheme.typography.bodyMedium)
                            Slider(value = minSilenceSec, onValueChange = { minSilenceSec = it }, valueRange = 0.3f..4f, steps = 36)
                        }
                    }
                    Text(stringResource(R.string.split_min_song, minSongSec.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = minSongSec, onValueChange = { minSongSec = it }, valueRange = 3f..90f, steps = 28)

                    Text(
                        if (chapters.size > 1) pluralStringResource(R.plurals.split_will_make, chapters.size, chapters.size)
                        else stringResource(if (canAutoSplit) R.string.split_no_split else R.string.split_no_split_manual),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (chapters.size > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chapters.forEachIndexed { i, ch ->
                            val start = if (i == 0) clipStart else ch.startMs
                            val end = chapters.getOrNull(i + 1)?.startMs ?: endMs
                            val isCurrent = previewing && playheadMs >= start && playheadMs < end
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                IconButton(onClick = {
                                    checkingCuts = false
                                    previewAt(start, minOf(start + 8000L, end))
                                }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.split_cd_listen), tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    ltr("${YouTubeChapters.formatTime(start)}–${YouTubeChapters.formatTime(end)}"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(92.dp)
                                )
                                OutlinedTextField(
                                    value = ch.title,
                                    onValueChange = { v -> names = names + (ch.startMs to v) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (i > 0) {
                                    IconButton(onClick = {
                                        manualCuts = manualCuts - ch.startMs
                                        removedCuts = removedCuts + ch.startMs
                                    }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.split_cd_remove_cut)) }
                                } else {
                                    Spacer(Modifier.size(48.dp))
                                }
                            }
                        }
                    }
                }
              }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Button(enabled = totalMs > 0 || initialChapters.isNotEmpty(), onClick = {
                        stopPreview()
                        val clip = if (totalMs <= 0) initialClip else Clip(
                            startMs = if (clipStart < EDGE_SNAP_MS) 0L else clipStart,
                            endMs = if (endMs > totalMs - EDGE_SNAP_MS) 0L else endMs
                        ).takeIf { it.isSet }
                        onDone(if (chapters.size > 1) chapters else emptyList(), clip)
                    }) { Text(stringResource(if (chapters.size > 1) R.string.split_use_songs else R.string.trim_use)) }
                }
            }
        }
    }
}

/** Keeps times and dB values reading left to right when they sit inside right-to-left text. */
private fun ltr(text: String): String = BidiFormatter.getInstance().unicodeWrap(text, TextDirectionHeuristicsCompat.LTR) ?: text

/** m:ss.t (or h:mm:ss.t): cuts are set to a quarter second. */
private fun ltrTenths(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val tenths = (t / 100) % 10
    val s = (t / 1000) % 60
    val m = (t / 60_000) % 60
    val h = t / 3_600_000
    return ltr(if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, tenths) else "%d:%02d.%d".format(m, s, tenths))
}

/** One end of the cut: ◀ earlier, the time, later ▶, listen. The arrows point the way the handle moves. */
@Composable
private fun EdgeRow(
    label: String,
    enabled: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onListen: () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(enabled = enabled, onClick = onEarlier) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.trim_earlier))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(enabled = enabled, onClick = onLater) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.trim_later))
            }
            IconButton(enabled = enabled, onClick = onListen) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.trim_listen), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Waveform(
    timeline: SilenceScanner.Timeline?,
    silences: List<SilenceScanner.Silence>,
    cuts: List<Long>,
    clipStart: Long,
    clipEnd: Long,
    activeHandle: Boolean?,
    playheadMs: Long,
    showPlayhead: Boolean,
    totalMs: Long,
    modifier: Modifier = Modifier
) {
    val barColor = Teal
    val cutAwayColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)
    val silenceColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    val shade = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (totalMs <= 0) return@Canvas
        val sx = w * clipStart / totalMs
        val ex = w * clipEnd / totalMs
        if (timeline != null && timeline.size > 0) {
            // Shade silences.
            for (s in silences) {
                val x0 = w * s.startMs / totalMs
                val x1 = w * s.endMs / totalMs
                drawRect(silenceColor, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), h))
            }
            // Loudness bars: one per ~2 px, peak of the windows in that span. Unanalysed spans stay empty.
            val bars = (w / 2f).toInt().coerceAtLeast(1)
            val perBar = (timeline.size / bars).coerceAtLeast(1)
            for (b in 0 until bars) {
                var peak = Float.NaN
                val from = b * perBar
                val to = minOf(from + perBar, timeline.size)
                if (from >= timeline.size) break
                for (i in from until to) {
                    val v = timeline.levelsDb[i]
                    if (!v.isNaN() && (peak.isNaN() || v > peak)) peak = v
                }
                if (peak.isNaN()) continue
                val norm = ((peak + 60f) / 60f).coerceIn(0f, 1f)
                val bh = norm * h
                val x = w * timeline.timeMs(from).toFloat() / totalMs
                drawRect(if (x in sx..ex) barColor else cutAwayColor, Offset(x, (h - bh) / 2f), Size(2f, bh.coerceAtLeast(1f)))
            }
        } else {
            // No waveform (a YouTube video): a plain bar for the part that plays.
            drawRect(barColor, Offset(sx, h * 0.4f), Size((ex - sx).coerceAtLeast(1f), h * 0.2f))
        }
        // Dim what the start / end cut leaves out.
        drawRect(shade, Offset(0f, 0f), Size(sx, h))
        drawRect(shade, Offset(ex, 0f), Size((w - ex).coerceAtLeast(0f), h))
        // Split lines.
        for (c in cuts) {
            val x = w * c / totalMs
            drawLine(Orange.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
        }
        // Start / end handles: a line with grips top and bottom, fatter while dragged.
        for ((x, isStart) in listOf(sx to true, ex to false)) {
            val active = activeHandle == isStart
            drawLine(Orange, Offset(x, 0f), Offset(x, h), strokeWidth = if (active) 6f else 4f)
            val gw = if (active) 28f else 22f
            val gh = if (active) 34f else 26f
            val gx = if (isStart) x else x - gw
            drawRoundRect(Orange, Offset(gx, 0f), Size(gw, gh), CornerRadius(8f, 8f))
            drawRoundRect(Orange, Offset(gx, h - gh), Size(gw, gh), CornerRadius(8f, 8f))
        }
        // Playhead.
        if (showPlayhead) {
            val px = w * playheadMs / totalMs
            drawLine(Berry, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
        }
    }
}
