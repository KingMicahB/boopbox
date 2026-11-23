package com.cardplayer.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cardplayer.R
import com.cardplayer.audio.VoiceRecorder
import com.cardplayer.data.Recordings
import com.cardplayer.link.YouTubeChapters
import com.cardplayer.ui.theme.Berry
import kotlinx.coroutines.delay
import java.io.File

/**
 * "Record it yourself" in the card editor: record with the phone's microphone (pause, carry on, done), listen to
 * the take, record it again.
 *
 * [uri] is the take the card has now ("" = none yet). A finished take is handed to [onTake]; until then the card
 * keeps the one it had. [onQuiet] is called before the microphone or the take is heard, to hush whatever is
 * playing. [onActive] says when a take is under way, so the editor can hold off saving and leaving.
 * [clipStartMs] / [clipEndMs] are the card's cut (0 end = to the end): Listen plays what the card will play.
 */
@Composable
internal fun RecorderPanel(
    uri: String,
    clipStartMs: Long,
    clipEndMs: Long,
    onQuiet: () -> Unit,
    onActive: (Boolean) -> Unit,
    onTake: (File) -> Unit
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val currentOnTake by rememberUpdatedState(onTake)
    val currentOnActive by rememberUpdatedState(onActive)
    val currentOnQuiet by rememberUpdatedState(onQuiet)

    var active by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    /** What went wrong last time, for the screen. */
    var problem by remember { mutableStateOf<String?>(null) }
    var micRefused by remember { mutableStateOf(false) }
    var confirmAgain by remember { mutableStateOf(false) }
    /** The file being recorded into, so a take that comes to nothing leaves no empty folder behind. */
    var takeFile by remember { mutableStateOf<File?>(null) }

    val hasTake = uri.isNotBlank()
    // The take the card has now, to listen to.
    val preview = remember(uri) { if (hasTake) FileSongPreview(context, uri) else null }
    var listening by remember(uri) { mutableStateOf(false) }
    DisposableEffect(preview) { onDispose { preview?.release() } }

    fun stopListening() {
        preview?.pause()
        listening = false
    }

    /** Ends the take under way and keeps it. */
    fun finish() {
        if (!recorder.active) return
        val file = recorder.finish()
        active = false
        paused = false
        currentOnActive(false)
        if (file != null) {
            currentOnTake(file)
        } else {
            takeFile?.parentFile?.delete()
            problem = context.getString(R.string.editor_rec_nothing)
        }
        takeFile = null
    }

    fun begin() {
        problem = null
        micRefused = false
        stopListening()
        currentOnQuiet()
        val file = Recordings.newFile(context)
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.parentFile?.delete()
            problem = context.getString(if (e is VoiceRecorder.NoRoomException) R.string.editor_rec_no_room else R.string.editor_rec_failed)
            return
        }
        takeFile = file
        elapsedMs = 0L
        level = 0f
        paused = false
        active = true
        currentOnActive(true)
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else micRefused = true
    }

    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        // As long as a take may be, or the phone is full: what there is, is kept.
        recorder.onStoppedItself = { finish() }
        onDispose {
            recorder.onStoppedItself = null
            // The panel went away mid-take (the card type was changed): nothing was asked to be kept.
            if (recorder.active) {
                recorder.cancel()
                takeFile?.parentFile?.delete()
                currentOnActive(false)
            }
        }
    }

    // Leaving the app or locking the phone ends the take; what was recorded is kept.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        finish()
        stopListening()
    }
    // Back, mid-take, means "done" rather than "leave the editor".
    BackHandler(enabled = active) { finish() }

    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(active, paused) {
        if (!active) return@LaunchedEffect
        while (true) {
            elapsedMs = recorder.elapsedMs()
            level = recorder.level()
            delay(80)
        }
    }

    // Listening stops by itself at the end of the take, or at the card's end cut.
    LaunchedEffect(listening, preview, clipEndMs) {
        val p = preview
        if (!listening || p == null) return@LaunchedEffect
        delay(400) // the player takes a moment to say it is playing
        while (p.isPlaying() && (clipEndMs <= 0L || p.positionMs() < clipEndMs)) delay(100)
        p.pause()
        listening = false
    }

    if (confirmAgain) {
        AlertDialog(
            onDismissRequest = { confirmAgain = false },
            title = { Text(stringResource(R.string.editor_rec_again_title)) },
            text = { Text(stringResource(R.string.editor_rec_again_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAgain = false
                    record()
                }) { Text(stringResource(R.string.editor_rec_again)) }
            },
            dismissButton = { TextButton(onClick = { confirmAgain = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    val hasMic = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }

    when {
        active -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordDot(if (paused) MaterialTheme.colorScheme.onSurfaceVariant else Berry)
                Spacer(Modifier.width(10.dp))
                Text(YouTubeChapters.formatTime(elapsedMs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (paused) R.string.editor_rec_paused else R.string.editor_rec_recording),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // How loud the microphone hears it: a bar that moves says it is working.
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (paused) recorder.resume() else recorder.pause()
                        paused = recorder.paused
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(if (paused) R.string.editor_rec_resume else R.string.editor_rec_pause)) }
                Button(onClick = { finish() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold)
                }
            }
        }

        hasTake -> {
            val length = preview?.durationMs ?: 0L
            Text(
                if (length > 0L) stringResource(R.string.editor_rec_take, YouTubeChapters.formatTime(length)) else stringResource(R.string.editor_rec_take_plain),
                style = MaterialTheme.typography.bodyLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = preview?.ready == true,
                    onClick = {
                        if (listening) {
                            stopListening()
                        } else {
                            currentOnQuiet()
                            preview?.play(clipStartMs)
                            listening = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (!listening) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(if (listening) R.string.editor_rec_stop_listening else R.string.editor_rec_listen))
                }
                OutlinedButton(onClick = { confirmAgain = true }, enabled = hasMic, modifier = Modifier.weight(1f)) {
                    RecordDot(Berry)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_rec_again))
                }
            }
        }

        else -> {
            Button(onClick = { record() }, enabled = hasMic, modifier = Modifier.fillMaxWidth()) {
                RecordDot(MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_rec_start), fontWeight = FontWeight.Bold)
            }
            Text(
                stringResource(if (hasMic) R.string.editor_rec_tip else R.string.editor_rec_no_mic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (micRefused) {
        Text(stringResource(R.string.editor_rec_mic_needed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
            }
        }) { Text(stringResource(R.string.editor_rec_mic_settings)) }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

/** The round "record" mark. */
@Composable
private fun RecordDot(color: Color) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
    )
}
