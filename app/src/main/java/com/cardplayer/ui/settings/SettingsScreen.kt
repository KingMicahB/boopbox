@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.cardplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardplayer.MainViewModel
import com.cardplayer.NfcState
import com.cardplayer.R
import com.cardplayer.billing.Plus
import com.cardplayer.billing.PlusReason
import com.cardplayer.data.AppSettings
import com.cardplayer.data.Backup
import com.cardplayer.data.BoopFile
import com.cardplayer.data.Card as CardModel
import com.cardplayer.playback.NowPlaying
import com.cardplayer.ui.CardArtwork
import com.cardplayer.ui.PauseIcon
import com.cardplayer.ui.PlusLockedRow
import com.cardplayer.ui.StopIcon
import com.cardplayer.util.Bedtime
import com.cardplayer.util.SystemSettings
import com.cardplayer.yoto.YotoClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The grown-up settings are split into sections; the hub shows one tile per section. */
private enum class Section(@param:StringRes val titleRes: Int, val emoji: String, @param:StringRes val blurbRes: Int) {
    PLUS(R.string.settings_section_plus_title, "⭐", R.string.settings_section_plus_blurb),
    CARDS(R.string.settings_section_cards_title, "🃏", R.string.settings_section_cards_blurb),
    PICKER(R.string.settings_section_picker_title, "👆", R.string.settings_section_picker_blurb),
    PLAYBACK(R.string.settings_section_playback_title, "▶️", R.string.settings_section_playback_blurb),
    ACCOUNTS(R.string.settings_section_accounts_title, "☁️", R.string.settings_section_accounts_blurb),
    SAFETY(R.string.settings_section_safety_title, "🔒", R.string.settings_section_safety_blurb),
    BEDTIME(R.string.settings_section_bedtime_title, "🌙", R.string.settings_section_bedtime_blurb),
    LOOK(R.string.settings_section_look_title, "🎨", R.string.settings_section_look_blurb),
    PERMISSIONS(R.string.settings_section_permissions_title, "✅", R.string.settings_section_permissions_blurb),
    HELP(R.string.settings_section_help_title, "💡", R.string.settings_section_help_blurb)
}

/** Layout numbers that follow the screen width. */
private class Metrics(val maxWidth: Dp) {
    val gutter: Dp get() = if (maxWidth < 360.dp) 12.dp else if (maxWidth < 600.dp) 16.dp else 24.dp
    val columns: Int get() = when {
        maxWidth >= 900.dp -> 3
        maxWidth >= 480.dp -> 2
        else -> 1
    }
    /** Content never gets wider than this, so tablets don't stretch switches across the whole screen. */
    val contentMax: Dp get() = 760.dp
    val artSize: Dp get() = if (maxWidth < 360.dp) 48.dp else 60.dp
}

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editPrompt by viewModel.editPrompt.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val plus by viewModel.plus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Notices (backup saved, .boop loaded, errors) land here while the grown-up is in settings.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbar.showSnackbar(it.text) } }

    var section by rememberSaveable { mutableStateOf<Section?>(null) }
    // Test builds only: a section asked for over adb (MainActivity.handleDebugIntent).
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    LaunchedEffect(debugSettings) { debugSettings?.let { asked -> section = Section.entries.firstOrNull { it.name == asked.first } } }
    BackHandler(enabled = section != null) { section = null }

    editPrompt?.let { card ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissEditPrompt() },
            title = { Text(stringResource(R.string.settings_edit_prompt_title, card.displayName(context))) },
            text = { Text(stringResource(R.string.settings_edit_prompt_body, stringResource(card.sourceType.labelRes))) },
            confirmButton = { TextButton(onClick = { viewModel.openEditor(card.uid) }) { Text(stringResource(R.string.settings_edit_card)) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissEditPrompt() }) { Text(stringResource(R.string.common_not_now)) } }
        )
    }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var mediaAccess by remember { mutableStateOf(viewModel.player.hasMediaSessionAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                mediaAccess = viewModel.player.hasMediaSessionAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sleepEnds by viewModel.player.sleepEndsAt.collectAsStateWithLifecycle()
    val sleepAtEnd by viewModel.player.sleepAtTrackEnd.collectAsStateWithLifecycle()
    val nfc by viewModel.nfc.collectAsStateWithLifecycle()
    val yotoTokens by viewModel.player.yoto.tokens.collectAsStateWithLifecycle()
    val cacheVersion by viewModel.player.podcastCache.version.collectAsStateWithLifecycle()
    val cacheMb = remember(cacheVersion) { "%.1f".format(viewModel.player.podcastCache.usedBytes() / 1_048_576f) }
    // The card list's search box: kept here so it survives scrolling the list.
    var cardQuery by rememberSaveable { mutableStateOf("") }
    val permissionsToFix = (if (nfc != NfcState.ON) 1 else 0) +
        (if (settings.returnToApp && settings.returnAfterSeconds > 0 && !overlayGranted) 1 else 0) +
        (if (!mediaAccess) 1 else 0)

    @Composable
    fun statusFor(s: Section): String = when (s) {
        Section.PLUS -> if (plus) stringResource(R.string.settings_status_plus_on) else stringResource(R.string.plus_cards_used, minOf(cards.size, Plus.FREE_CARD_LIMIT), Plus.FREE_CARD_LIMIT)
        Section.CARDS -> if (cards.isEmpty()) stringResource(R.string.settings_status_no_cards) else pluralStringResource(R.plurals.settings_status_cards, cards.size, cards.size)
        Section.PICKER -> if (!settings.holdLogoToPick) stringResource(R.string.settings_off) else stringResource(
            R.string.settings_picker_status,
            stringResource(if (settings.pickerStyle == "tiles") R.string.settings_picker_style_tiles else R.string.settings_picker_style_cards),
            cards.count { it.showInPicker },
            cards.size
        )
        Section.PLAYBACK -> if (settings.volumeCapPercent >= 100) stringResource(R.string.settings_status_volume_off) else stringResource(R.string.settings_status_volume_limit, settings.volumeCapPercent)
        Section.ACCOUNTS -> stringResource(if (yotoTokens != null) R.string.settings_status_yoto_connected else R.string.settings_status_yoto_not_connected) +
            " · " + stringResource(R.string.settings_podcasts_using, cacheMb)
        Section.SAFETY -> listOfNotNull(
            if (settings.childLock) stringResource(R.string.settings_status_child_lock_on) else null,
            stringResource(
                when (settings.cardRemovedMode) {
                    "stop" -> R.string.settings_status_new_cards_stop
                    "pause" -> R.string.settings_status_new_cards_pause
                    else -> R.string.settings_status_new_cards_keep
                }
            )
        ).joinToString(" · ")
        Section.BEDTIME -> listOf(
            when {
                sleepAtEnd -> stringResource(R.string.settings_sleep_after_song)
                sleepEnds != null -> stringResource(R.string.settings_status_sleep_running)
                settings.bedtimeEnabled && plus -> stringResource(R.string.settings_status_bedtime_hours, Bedtime.formatMinutes(settings.bedtimeStart), Bedtime.formatMinutes(settings.bedtimeEnd))
                else -> stringResource(R.string.settings_off)
            },
            stringResource(
                when (settings.nightLight) {
                    "off" -> R.string.settings_status_night_light_off
                    "on" -> R.string.settings_status_night_light_on
                    else -> R.string.settings_status_night_light_bedtime
                }
            )
        ).joinToString(" · ")
        Section.LOOK -> stringResource(
            when (settings.themeMode) {
                "dark" -> R.string.settings_theme_dark
                "light" -> R.string.settings_theme_light
                else -> R.string.settings_status_follows_phone
            }
        )
        Section.PERMISSIONS -> if (permissionsToFix == 0) stringResource(R.string.settings_status_all_set) else stringResource(R.string.settings_status_to_fix, permissionsToFix)
        Section.HELP -> stringResource(R.string.settings_status_help)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = Metrics(maxWidth)
        val current = section
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current?.titleRes ?: R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { if (current == null) viewModel.goToPlayer() else section = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(if (current == null) R.string.settings_back_to_player else R.string.settings_back_to_settings))
                        }
                    }
                )
            },
            floatingActionButton = {
                if (current == null || current == Section.CARDS) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.openScan() },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.settings_add_card)) }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            // Whatever the toddler left playing stays in reach while the grown-up is in here.
            bottomBar = { nowPlaying?.let { MiniPlayer(np = it, pixel = settings.pixelArt, viewModel = viewModel) } }
        ) { padding ->
            val contentPadding = PaddingValues(
                start = m.gutter,
                end = m.gutter,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            )
            if (current == null) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(m.columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Once Plus is bought there is nothing left to sell: drop its tile. Debug builds keep it for the pretend switch.
                    val sections = Section.entries.filter { it != Section.PLUS || !plus || Plus.canPretend }
                    items(sections, key = { it.name }) { s ->
                        SectionTile(
                            section = s,
                            status = statusFor(s),
                            attention = s == Section.PERMISSIONS && permissionsToFix > 0,
                            onClick = { section = s }
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // Each section starts at its top.
                    val listState = key(current) { rememberLazyListState() }
                    LaunchedEffect(debugSettings, current) {
                        val asked = debugSettings ?: return@LaunchedEffect
                        if (asked.first == current.name && asked.second > 0) {
                            delay(150)
                            listState.scrollToItem(asked.second)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = m.contentMax),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (current) {
                            Section.PLUS -> plusSection(plus, cards.size, viewModel)
                            Section.CARDS -> cardsSection(cards, settings, m, viewModel, cardQuery) { cardQuery = it }
                            Section.PICKER -> pickerSection(cards, settings, context, viewModel)
                            Section.PLAYBACK -> playbackSection(settings, viewModel)
                            Section.ACCOUNTS -> accountsSection(settings, viewModel)
                            Section.SAFETY -> safetySection(settings, viewModel)
                            Section.BEDTIME -> bedtimeSection(settings, plus, viewModel)
                            Section.LOOK -> lookSection(settings, viewModel)
                            Section.PERMISSIONS -> permissionsSection(settings, nfc, overlayGranted, mediaAccess, context)
                            Section.HELP -> helpSection()
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- mini player

/** One row at the bottom of every settings screen: what is playing, play / pause, stop, sleep timer. */
@Composable
private fun MiniPlayer(np: NowPlaying, pixel: Boolean, viewModel: MainViewModel) {
    val sleepEnds by viewModel.player.sleepEndsAt.collectAsStateWithLifecycle()
    val sleepAtEnd by viewModel.player.sleepAtTrackEnd.collectAsStateWithLifecycle()
    var sleepMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sleepText = when {
        sleepAtEnd -> stringResource(R.string.settings_mini_sleep_after_song)
        sleepEnds != null -> stringResource(R.string.settings_mini_sleep_minutes, ((sleepEnds!! - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0))
        else -> null
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardArtwork(
                artwork = np.artworkBytes ?: np.artworkBitmap ?: np.artworkUrl,
                seed = np.trackSeed,
                color = np.card.color,
                modifier = Modifier.size(44.dp),
                cornerRadius = 10.dp,
                pixel = pixel
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        np.card.displayName(context).takeIf { it != np.title },
                        sleepText ?: stringResource(if (np.isPlaying) R.string.settings_mini_playing else R.string.settings_mini_paused)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { sleepMenu = true }) { Text("🌙", fontSize = 20.sp) }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_sleep_timer_off)) }, onClick = { viewModel.player.cancelSleep(); sleepMenu = false })
                    listOf(15, 30, 45, 60).forEach { mins ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_sleep_in_minutes, mins)) }, onClick = { viewModel.player.setSleepTimer(mins); sleepMenu = false })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_sleep_after_song)) }, onClick = { viewModel.player.setSleepAtTrackEnd(); sleepMenu = false })
                }
            }
            IconButton(onClick = { viewModel.player.togglePlayPause() }) {
                if (np.isPlaying) PauseIcon(modifier = Modifier.size(width = 16.dp, height = 18.dp), color = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.settings_play), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { viewModel.player.stop() }) {
                StopIcon(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------- hub tiles

@Composable
private fun SectionTile(section: Section, status: String, attention: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (attention) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(section.emoji, fontSize = 32.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(section.blurbRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- sections

private fun LazyListScope.cardsSection(
    cards: List<CardModel>,
    settings: AppSettings,
    m: Metrics,
    viewModel: MainViewModel,
    query: String,
    onQuery: (String) -> Unit
) {
    if (cards.isEmpty()) {
        item {
            Text(
                stringResource(R.string.settings_cards_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (cards.isNotEmpty()) {
        item { SubTitle(stringResource(R.string.settings_your_cards)) }
        item {
            Column {
                ChipGroup(
                    options = listOf(
                        "recent" to stringResource(R.string.settings_sort_recent),
                        "name" to stringResource(R.string.settings_sort_name),
                        "added" to stringResource(R.string.settings_sort_added)
                    ),
                    selected = settings.cardSort
                ) { v -> viewModel.updateSettings { it.copy(cardSort = v) } }
                if (cards.size > 6) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_search_cards)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = null) } }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }
    val q = query.trim()
    val shown = cards
        .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.subtitle?.contains(q, ignoreCase = true) == true }
        .let { list ->
            when (settings.cardSort) {
                "name" -> list.sortedBy { it.name.ifBlank { it.subtitle ?: "" }.lowercase() }
                "added" -> list.sortedByDescending { it.createdAt }
                else -> list.sortedWith(compareByDescending<CardModel> { it.lastPlayedAt }.thenByDescending { it.createdAt })
            }
        }
    if (cards.isNotEmpty() && shown.isEmpty()) {
        item {
            Text(stringResource(R.string.settings_no_matching_cards), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(shown, key = { it.uid }) { card ->
        val context = LocalContext.current
        CardRow(
            card = card,
            pixel = settings.pixelArt,
            artSize = m.artSize,
            onClick = { viewModel.openEditor(card.uid) },
            // Quick share: pack it as a .boop and open the share sheet.
            onShare = { viewModel.exportBoop(card) { file -> context.startActivity(BoopFile.shareIntent(context, file)) } },
            onPlay = { viewModel.playNow(card) },
            extra = { YouTubeCacheStatus(viewModel.player.youtubeCache, card, settings.youtubeAutoCache) }
        )
    }
    // Last: .boop files normally open straight from WhatsApp, email or Files; this is for the app that won't hand one over.
    item {
        val busy by viewModel.boopBusy.collectAsStateWithLifecycle()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.receiveBoop(uri)
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.settings_load_boop)) }
                InfoIcon(text = stringResource(R.string.settings_load_boop_hint), title = stringResource(R.string.settings_load_boop))
            }
            if (busy) {
                Text(
                    stringResource(R.string.settings_load_boop_unpacking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** The card picker: playing a card from its picture when the card itself isn't there (hold the logo on the home screen). */
private fun LazyListScope.pickerSection(cards: List<CardModel>, settings: AppSettings, context: Context, viewModel: MainViewModel) {
    item {
        SettingsGroup {
            SwitchSetting(
                title = stringResource(R.string.settings_logo_picker_title),
                info = stringResource(R.string.settings_logo_picker_subtitle),
                checked = settings.holdLogoToPick
            ) { v -> viewModel.updateSettings { it.copy(holdLogoToPick = v) } }
            if (settings.holdLogoToPick) {
                SettingRow(
                    title = stringResource(R.string.settings_picker_style),
                    below = {
                        Segments(
                            options = listOf(
                                "cards" to stringResource(R.string.settings_picker_style_cards),
                                "tiles" to stringResource(R.string.settings_picker_style_tiles)
                            ),
                            selected = if (settings.pickerStyle == "tiles") "tiles" else "cards"
                        ) { v -> viewModel.updateSettings { it.copy(pickerStyle = v) } }
                    }
                )
                if (settings.pickerStyle == "tiles") {
                    // Across × down.
                    SettingRow(title = stringResource(R.string.settings_picker_tiles_per_screen)) {
                        val sizes = (1..6).map { it to it.toString() }
                        ValueMenu(sizes, settings.pickerTileCols) { v -> viewModel.updateSettings { it.copy(pickerTileCols = v) } }
                        Text("×", style = MaterialTheme.typography.titleMedium)
                        ValueMenu(sizes, settings.pickerTileRows) { v -> viewModel.updateSettings { it.copy(pickerTileRows = v) } }
                    }
                }
            }
        }
    }
    if (!settings.holdLogoToPick || cards.isEmpty()) return
    item {
        SettingsGroup(title = stringResource(R.string.settings_picker_cards), info = stringResource(R.string.settings_picker_cards_hint)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { viewModel.setShowInPicker(cards.map { it.uid }, true) },
                    enabled = cards.any { !it.showInPicker }
                ) { Text(stringResource(R.string.settings_picker_show_all)) }
                TextButton(
                    onClick = { viewModel.setShowInPicker(cards.map { it.uid }, false) },
                    enabled = cards.any { it.showInPicker }
                ) { Text(stringResource(R.string.settings_picker_hide_all)) }
            }
            // By name, so a card keeps its place in the list when its switch is flipped.
            for (card in cards.sortedBy { it.displayName(context).lowercase() }) {
                key(card.uid) {
                    SwitchSetting(
                        title = card.displayName(context),
                        checked = card.showInPicker,
                        leading = {
                            CardArtwork(
                                artwork = card.artworkUrl,
                                seed = card.uid,
                                color = card.color,
                                modifier = Modifier.size(44.dp),
                                cornerRadius = 10.dp,
                                pixel = settings.pixelArt
                            )
                        }
                    ) { v -> viewModel.setShowInPicker(listOf(card.uid), v) }
                }
            }
        }
    }
}

private fun LazyListScope.plusSection(plus: Boolean, cardCount: Int, viewModel: MainViewModel) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plus) {
                Text(stringResource(R.string.plus_active), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    stringResource(R.string.plus_cards_used, minOf(cardCount, Plus.FREE_CARD_LIMIT), Plus.FREE_CARD_LIMIT),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.plus_once),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Button(onClick = { viewModel.showPaywall(PlusReason.GENERAL) }) {
                    val price by viewModel.plusPrice.collectAsStateWithLifecycle()
                    Text(price?.let { stringResource(R.string.plus_buy, it) } ?: stringResource(R.string.plus_buy_no_price))
                }
            }
            OutlinedButton(onClick = { Plus.refresh() }) { Text(stringResource(R.string.plus_restore)) }
        }
    }
    if (Plus.canPretend) {
        item {
            val pretending by Plus.pretending.collectAsStateWithLifecycle()
            SettingsGroup {
                SwitchSetting(
                    title = stringResource(R.string.plus_pretend_title),
                    note = stringResource(R.string.plus_pretend_subtitle),
                    checked = pretending
                ) { Plus.setPretend(it) }
            }
        }
    }
}

private fun LazyListScope.playbackSection(settings: AppSettings, viewModel: MainViewModel) {
    item {
        SettingsGroup(stringResource(R.string.settings_group_player)) {
            SettingRow(
                title = if (settings.volumeCapPercent >= 100) stringResource(R.string.settings_volume_limit_off) else stringResource(R.string.settings_volume_limit_percent, settings.volumeCapPercent),
                below = {
                    SettingSlider(value = settings.volumeCapPercent.toFloat(), valueRange = 20f..100f, steps = 15) { v -> viewModel.updateSettings { it.copy(volumeCapPercent = v.roundToInt()) } }
                }
            )
            SwitchSetting(
                title = stringResource(R.string.settings_keep_screen_on),
                checked = settings.keepScreenOn
            ) { v -> viewModel.updateSettings { it.copy(keepScreenOn = v) } }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_section_cards_title)) {
            SwitchSetting(
                title = stringResource(R.string.settings_same_card_title),
                checked = settings.sameCardToggles
            ) { v -> viewModel.updateSettings { it.copy(sameCardToggles = v) } }
            SwitchSetting(
                title = stringResource(R.string.settings_default_resume_title),
                info = stringResource(R.string.settings_default_resume_subtitle),
                checked = settings.defaultResume
            ) { v -> viewModel.updateSettings { it.copy(defaultResume = v) } }
            // Picture-only (Safety) turns the drag off along with the other zones.
            if (!settings.pictureOnly) {
                SwitchSetting(
                    title = stringResource(R.string.settings_scrub_title),
                    info = stringResource(R.string.settings_scrub_subtitle),
                    checked = settings.dragToScrub
                ) { v -> viewModel.updateSettings { it.copy(dragToScrub = v) } }
            }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_group_card_lifted)) {
            val modes = listOf(
                "stop" to stringResource(R.string.settings_card_removed_stop),
                "pause" to stringResource(R.string.settings_card_removed_pause),
                "nothing" to stringResource(R.string.settings_card_removed_keep)
            )
            SettingRow(
                title = stringResource(R.string.settings_card_removed_title),
                info = stringResource(R.string.settings_card_removed_hint),
                below = {
                    Segments(options = modes, selected = settings.cardRemovedMode) { mode -> viewModel.updateSettings { it.copy(cardRemoved = mode) } }
                }
            )
            SettingRow(title = stringResource(R.string.settings_card_removed_override_title)) {
                ValueMenu(
                    options = listOf<Pair<String?, String>>(null to stringResource(R.string.settings_card_removed_override_none)) + modes,
                    selected = settings.cardRemovedOverride
                ) { mode -> viewModel.updateSettings { it.copy(cardRemovedOverride = mode) } }
            }
            SettingRow(
                title = stringResource(R.string.settings_card_removed_grace_title, settings.cardRemovedGraceSeconds),
                info = stringResource(R.string.settings_card_removed_grace, settings.cardRemovedGraceSeconds),
                below = {
                    SettingSlider(value = settings.cardRemovedGraceSeconds.toFloat(), valueRange = 1f..30f, steps = 28) { v -> viewModel.updateSettings { it.copy(cardRemovedGraceSeconds = v.roundToInt()) } }
                }
            )
            SwitchSetting(
                title = stringResource(R.string.settings_car_card_lift_title),
                info = stringResource(R.string.settings_car_card_lift_subtitle),
                checked = settings.carIgnoreCardLift
            ) { v -> viewModel.updateSettings { it.copy(carIgnoreCardLift = v) } }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_external_apps)) {
            SwitchSetting(
                title = stringResource(R.string.settings_return_title),
                info = stringResource(R.string.settings_return_subtitle),
                checked = settings.returnToApp
            ) { v -> viewModel.updateSettings { it.copy(returnToApp = v) } }
            if (settings.returnToApp) {
                val secs = settings.returnAfterSeconds
                SettingRow(
                    title = if (secs <= 0) stringResource(R.string.settings_return_now) else stringResource(R.string.settings_return_after, secs),
                    below = {
                        SettingSlider(value = secs.toFloat(), valueRange = 0f..10f, steps = 9) { v -> viewModel.updateSettings { it.copy(returnAfterSeconds = v.roundToInt()) } }
                    }
                )
            }
            SwitchSetting(
                title = stringResource(R.string.settings_stop_autoplay_title),
                info = stringResource(R.string.settings_stop_autoplay_subtitle),
                checked = settings.stopExternalAutoplay
            ) { v -> viewModel.updateSettings { it.copy(stopExternalAutoplay = v) } }
        }
    }
}

/** Yoto sign-in, the podcast download store and the backup: accounts and the phone's data, apart from how things play. */
private fun LazyListScope.accountsSection(settings: AppSettings, viewModel: MainViewModel) {
    item {
        val yoto = viewModel.player.yoto
        val tokens by yoto.tokens.collectAsStateWithLifecycle()
        val login by yoto.loginState.collectAsStateWithLifecycle()
        SettingsGroup(title = stringResource(R.string.settings_yoto_title), info = stringResource(R.string.settings_yoto_body)) {
            SettingRow(
                title = stringResource(if (tokens != null) R.string.settings_yoto_connected else R.string.settings_yoto_not_connected),
                note = (login as? YotoClient.LoginState.Error)?.message
            ) {
                if (tokens == null) {
                    if (login == YotoClient.LoginState.Waiting) {
                        TextButton(onClick = { yoto.cancelLogin() }) { Text(stringResource(R.string.common_cancel)) }
                    }
                    TextButton(onClick = { yoto.startLogin() }, enabled = login != YotoClient.LoginState.Waiting) {
                        Text(stringResource(if (login == YotoClient.LoginState.Waiting) R.string.settings_yoto_waiting else R.string.settings_yoto_connect))
                    }
                } else {
                    TextButton(onClick = { yoto.logout() }) { Text(stringResource(R.string.settings_yoto_disconnect)) }
                }
            }
        }
    }
    item {
        val cache = viewModel.player.podcastCache
        val version by cache.version.collectAsStateWithLifecycle()
        val downloading by cache.downloading.collectAsStateWithLifecycle()
        val used = remember(version) { cache.usedBytes() }
        SettingsGroup(stringResource(R.string.settings_podcasts_title)) {
            SwitchSetting(
                title = stringResource(R.string.settings_podcasts_keep_title),
                info = stringResource(R.string.settings_podcasts_keep_subtitle),
                checked = settings.podcastAutoDownload
            ) { v -> viewModel.updateSettings { it.copy(podcastAutoDownload = v) } }
            SettingRow(
                title = stringResource(R.string.settings_podcasts_refresh),
                note = "%.1f".format(used / 1_048_576f).let { mb ->
                    if (downloading.isNotEmpty()) pluralStringResource(R.plurals.settings_podcasts_downloading, downloading.size, downloading.size, mb)
                    else stringResource(R.string.settings_podcasts_using, mb)
                },
                onClick = { cache.refreshAllAsync(viewModel.cards.value, settings.podcastAutoDownload, minIntervalMs = 0) }
            )
            SettingRow(title = stringResource(R.string.settings_podcasts_delete), onClick = { cache.clearAll() })
        }
    }
    // Save everything to one file, or put such a file back.
    item {
        val busy by viewModel.boopBusy.collectAsStateWithLifecycle()
        var confirmRestore by remember { mutableStateOf<Uri?>(null) }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) viewModel.exportBackup(uri)
        }
        val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestore = uri
        }
        SettingsGroup(title = stringResource(R.string.settings_backup_title), info = stringResource(R.string.settings_backup_body)) {
            SettingRow(
                title = stringResource(R.string.settings_backup_save),
                onClick = if (busy) null else ({ saver.launch(Backup.fileName()) })
            )
            SettingRow(
                title = stringResource(R.string.settings_backup_restore),
                onClick = if (busy) null else ({ opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
            )
        }
        confirmRestore?.let { uri ->
            AlertDialog(
                onDismissRequest = { confirmRestore = null },
                title = { Text(stringResource(R.string.settings_restore_title)) },
                text = { Text(stringResource(R.string.settings_restore_body)) },
                confirmButton = { TextButton(onClick = { viewModel.importBackup(uri); confirmRestore = null }) { Text(stringResource(R.string.settings_restore_confirm)) } },
                dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }
}

private fun LazyListScope.safetySection(settings: AppSettings, viewModel: MainViewModel) {
    item {
        var settingPin by remember { mutableStateOf(false) }
        val locked = SettingsLock.isLocked(settings)
        SettingsGroup(stringResource(R.string.settings_group_lock)) {
            SwitchSetting(
                title = stringResource(R.string.settings_child_lock_title),
                info = stringResource(R.string.settings_child_lock_subtitle),
                checked = settings.childLock
            ) { v -> viewModel.updateSettings { it.copy(childLock = v) } }
            if (settings.childLock) {
                SettingRow(title = stringResource(R.string.settings_unpin_now), onClick = { viewModel.player.hooks?.setChildLock(false) })
            }
            SettingRow(
                title = stringResource(R.string.settings_password_title),
                note = stringResource(if (locked) R.string.settings_on else R.string.settings_off),
                info = stringResource(if (locked) R.string.settings_password_on else R.string.settings_password_off)
            ) {
                if (locked) {
                    TextButton(onClick = { viewModel.updateSettings { SettingsLock.withPassword(it, null) } }) { Text(stringResource(R.string.common_remove)) }
                }
                TextButton(onClick = { settingPin = true }) { Text(stringResource(if (locked) R.string.settings_password_change else R.string.settings_password_set)) }
            }
            SettingRow(
                title = stringResource(R.string.settings_gear_taps_title),
                below = {
                    Segments(
                        options = listOf(2 to stringResource(R.string.settings_gear_double), 1 to stringResource(R.string.settings_gear_single)),
                        selected = settings.gearTaps
                    ) { v -> viewModel.updateSettings { it.copy(gearTaps = v) } }
                }
            )
            SwitchSetting(
                title = stringResource(R.string.settings_gate_title),
                checked = settings.gateUnknownCardSetup
            ) { v -> viewModel.updateSettings { it.copy(gateUnknownCardSetup = v) } }
        }
        if (settingPin) {
            SetPasswordDialog(
                onSave = { pw -> viewModel.updateSettings { SettingsLock.withPassword(it, pw) }; settingPin = false },
                onDismiss = { settingPin = false }
            )
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_group_touch)) {
            SettingRow(
                title = stringResource(R.string.settings_grip_title),
                note = stringResource(R.string.settings_grip_short),
                info = stringResource(R.string.settings_grip_body),
                below = {
                    Segments(
                        options = listOf(
                            "off" to stringResource(R.string.settings_off),
                            "normal" to stringResource(R.string.settings_grip_normal),
                            "strong" to stringResource(R.string.settings_grip_strong)
                        ),
                        selected = settings.gripGuard
                    ) { v -> viewModel.updateSettings { it.copy(gripGuard = v) } }
                }
            )
            SwitchSetting(
                title = stringResource(R.string.settings_picture_only_title),
                info = stringResource(R.string.settings_picture_only_subtitle),
                checked = settings.pictureOnly
            ) { v -> viewModel.updateSettings { it.copy(pictureOnly = v) } }
        }
    }
}

private fun LazyListScope.bedtimeSection(settings: AppSettings, plus: Boolean, viewModel: MainViewModel) {
    item {
        val sleepEnds by viewModel.player.sleepEndsAt.collectAsStateWithLifecycle()
        val sleepAtEnd by viewModel.player.sleepAtTrackEnd.collectAsStateWithLifecycle()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(sleepEnds) {
            while (sleepEnds != null) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        val state = when {
            sleepAtEnd -> stringResource(R.string.settings_sleep_state_after_song)
            sleepEnds != null -> {
                val secs = ((sleepEnds!! - now) / 1000).coerceAtLeast(0)
                stringResource(R.string.settings_sleep_state_left, secs / 60, secs % 60)
            }
            else -> stringResource(R.string.settings_sleep_state_off)
        }
        SettingsGroup {
            SettingRow(
                title = state,
                info = stringResource(R.string.settings_sleep_hint),
                below = {
                    // One line that slides sideways, rather than chips wrapping onto a ragged second line.
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = sleepEnds == null && !sleepAtEnd, onClick = { viewModel.player.cancelSleep() }, label = { Text(stringResource(R.string.settings_off)) })
                        listOf(15, 30, 45, 60).forEach { mins ->
                            FilterChip(selected = false, onClick = { viewModel.player.setSleepTimer(mins) }, label = { Text(stringResource(R.string.settings_minutes_short, mins)) })
                        }
                        FilterChip(selected = sleepAtEnd, onClick = { viewModel.player.setSleepAtTrackEnd() }, label = { Text(stringResource(R.string.settings_song_end)) })
                    }
                }
            )
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_bedtime_hours)) {
            if (!plus) {
                LockedRow(stringResource(R.string.settings_bedtime_hours)) { viewModel.showPaywall(PlusReason.BEDTIME) }
            } else {
                SwitchSetting(
                    title = stringResource(R.string.settings_bedtime_use),
                    note = if (!settings.bedtimeEnabled) null else stringResource(if (Bedtime.isBedtime(settings)) R.string.settings_bedtime_now else R.string.settings_bedtime_not_now),
                    info = stringResource(R.string.settings_bedtime_hours_hint),
                    checked = settings.bedtimeEnabled
                ) { v -> viewModel.updateSettings { it.copy(bedtimeEnabled = v) } }
                if (settings.bedtimeEnabled) {
                    TimeRow(stringResource(R.string.settings_bedtime_starts), settings.bedtimeStart) { v -> viewModel.updateSettings { it.copy(bedtimeStart = v) } }
                    TimeRow(stringResource(R.string.settings_bedtime_ends), settings.bedtimeEnd) { v -> viewModel.updateSettings { it.copy(bedtimeEnd = v) } }
                    SettingRow(
                        title = stringResource(R.string.settings_bedtime_volume, settings.bedtimeVolumeCapPercent),
                        below = {
                            SettingSlider(value = settings.bedtimeVolumeCapPercent.toFloat(), valueRange = 10f..100f, steps = 17) { v -> viewModel.updateSettings { it.copy(bedtimeVolumeCapPercent = v.roundToInt()) } }
                        }
                    )
                    SettingRow(title = stringResource(R.string.settings_bedtime_card_sleep_title)) {
                        ValueMenu(
                            options = listOf(0, 15, 30, 45, 60).map {
                                it to if (it == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_minutes_short, it)
                            },
                            selected = settings.bedtimeSleepMinutes
                        ) { v -> viewModel.updateSettings { it.copy(bedtimeSleepMinutes = v) } }
                    }
                }
            }
        }
    }
    item {
        SettingsGroup {
            SettingRow(
                title = stringResource(R.string.settings_night_light),
                below = {
                    Segments(
                        options = listOf(
                            "off" to stringResource(R.string.settings_off),
                            "on" to stringResource(R.string.settings_on),
                            "bedtime" to stringResource(R.string.settings_night_light_bedtime)
                        ),
                        selected = settings.nightLight
                    ) { mode -> viewModel.updateSettings { it.copy(nightLight = mode) } }
                }
            )
            // Its colour and brightness are Plus.
            if (settings.nightLight != "off" && !plus) {
                LockedRow(stringResource(R.string.plus_feature_night_light)) { viewModel.showPaywall(PlusReason.NIGHT_LIGHT) }
            }
            if (settings.nightLight != "off" && plus) {
                SettingRow(
                    title = stringResource(R.string.settings_night_light_colour),
                    below = {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(0xFFFF8C3AL, 0xFFFF4D4DL, 0xFFB07CFFL, 0xFF5FD3A0L, 0xFFFFC857L).forEach { c ->
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .border(if (settings.nightLightColor == c) 4.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                        .clickable { viewModel.updateSettings { it.copy(nightLightColor = c) } }
                                )
                            }
                        }
                    }
                )
                SettingRow(
                    title = stringResource(R.string.settings_brightness, settings.nightLightBrightness),
                    below = {
                        SettingSlider(value = settings.nightLightBrightness.toFloat(), valueRange = 5f..100f, steps = 18) { v -> viewModel.updateSettings { it.copy(nightLightBrightness = v.roundToInt()) } }
                    }
                )
            }
        }
    }
}

private fun LazyListScope.lookSection(settings: AppSettings, viewModel: MainViewModel) {
    item {
        SettingsGroup(stringResource(R.string.settings_group_look)) {
            SettingRow(
                title = stringResource(R.string.settings_theme_title),
                below = {
                    Segments(
                        options = listOf(
                            "system" to stringResource(R.string.settings_theme_system),
                            "light" to stringResource(R.string.settings_theme_light),
                            "dark" to stringResource(R.string.settings_theme_dark)
                        ),
                        selected = settings.themeMode
                    ) { mode -> viewModel.updateSettings { it.copy(themeMode = mode) } }
                }
            )
            SwitchSetting(
                title = stringResource(R.string.settings_pixel_art_title),
                checked = settings.pixelArt
            ) { v -> viewModel.updateSettings { it.copy(pixelArt = v) } }
            SettingRow(
                title = stringResource(R.string.settings_battery_title),
                below = {
                    Segments(
                        options = listOf(
                            "off" to stringResource(R.string.settings_off),
                            "low" to stringResource(R.string.settings_battery_low),
                            "always" to stringResource(R.string.settings_battery_always)
                        ),
                        selected = settings.showBattery
                    ) { mode -> viewModel.updateSettings { it.copy(showBattery = mode) } }
                }
            )
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_taps)) {
            SwitchSetting(
                title = stringResource(R.string.settings_flash_title),
                info = stringResource(R.string.settings_flash_subtitle),
                checked = settings.zoneFlash
            ) { v -> viewModel.updateSettings { it.copy(zoneFlash = v) } }
            SwitchSetting(
                title = stringResource(R.string.settings_buzz_title),
                checked = settings.zoneHaptics
            ) { v -> viewModel.updateSettings { it.copy(zoneHaptics = v) } }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_idle_screen)) {
            SwitchSetting(
                title = stringResource(R.string.settings_show_clock),
                checked = settings.showClock
            ) { v -> viewModel.updateSettings { it.copy(showClock = v) } }
        }
    }
}

/** A Plus feature that isn't unlocked, as a row of a group: tapping it opens the Plus sheet. */
@Composable
private fun LockedRow(title: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp)
    ) { PlusLockedRow(title, onClick) }
}

private fun LazyListScope.permissionsSection(settings: AppSettings, nfc: NfcState, overlayGranted: Boolean, mediaAccess: Boolean, context: Context) {
    item {
        PermissionRow(
            title = stringResource(R.string.settings_perm_nfc_title),
            granted = if (nfc == NfcState.MISSING) null else nfc == NfcState.ON,
            why = stringResource(
                when (nfc) {
                    NfcState.MISSING -> R.string.settings_perm_nfc_missing
                    NfcState.OFF -> R.string.settings_perm_nfc_off
                    NfcState.ON -> R.string.settings_perm_nfc_on
                }
            ),
            action = stringResource(R.string.settings_perm_nfc_action)
        ) { SystemSettings.openNfc(context) }
    }
    item {
        PermissionRow(
            title = stringResource(R.string.settings_perm_notif_title),
            granted = mediaAccess,
            why = stringResource(R.string.settings_perm_notif_why),
            action = stringResource(R.string.settings_perm_notif_action)
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
    item {
        val needed = settings.returnToApp && settings.returnAfterSeconds > 0
        PermissionRow(
            title = stringResource(R.string.settings_perm_overlay_title),
            granted = overlayGranted,
            why = stringResource(if (needed) R.string.settings_perm_overlay_needed else R.string.settings_perm_overlay_not_needed),
            action = stringResource(R.string.settings_perm_overlay_action)
        ) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
    }
    if (isXiaomi()) {
        item {
            PermissionRow(
                title = stringResource(R.string.settings_perm_xiaomi_title),
                granted = null,
                why = stringResource(R.string.settings_perm_xiaomi_why),
                action = stringResource(R.string.settings_perm_xiaomi_action)
            ) { openXiaomiPermissionEditor(context) }
        }
    }
    item {
        PermissionRow(
            title = stringResource(R.string.settings_perm_battery_title),
            granted = null,
            why = stringResource(R.string.settings_perm_battery_why),
            action = stringResource(R.string.settings_perm_battery_action)
        ) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}

private fun LazyListScope.helpSection() {
    val tips = listOf(
        R.string.settings_help_player_title to R.string.settings_help_player_body,
        R.string.settings_help_cards_title to R.string.settings_help_cards_body,
        R.string.settings_help_pictures_title to R.string.settings_help_pictures_body,
        R.string.settings_help_songs_title to R.string.settings_help_songs_body,
        R.string.settings_help_apps_title to R.string.settings_help_apps_body,
        R.string.settings_help_gear_title to R.string.settings_help_gear_body
    )
    items(tips) { (title, body) ->
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun SubTitle(text: String, info: String? = null) {
    InfoTitle(
        title = text,
        info = info,
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Wrapping group of single-select chips. */
@Composable
private fun ChipGroup(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun PermissionRow(title: String, granted: Boolean?, why: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (granted) {
                        true -> "✅"
                        false -> "⚠️"
                        null -> "ℹ️"
                    },
                    fontSize = 22.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (granted != true) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
            }
        }
    }
}

internal fun isXiaomi(): Boolean {
    val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
}

/** Opens MIUI/HyperOS's per-app "Other permissions" page, which holds the background pop-up toggle. */
internal fun openXiaomiPermissionEditor(context: Context) {
    val attempts = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", context.packageName)
        },
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", context.packageName)
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}

/** A time of day (minutes from midnight) with a proper clock picker behind "Change". */
@Composable
private fun TimeRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title = label) {
        TextButton(onClick = { open = true }) { Text(Bedtime.formatMinutes(minutes), style = MaterialTheme.typography.titleMedium) }
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    open = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Used by the card editor as well. */
@Composable
fun SectionTitle(text: String, info: String? = null) {
    InfoTitle(
        title = text,
        info = info,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun CardRow(
    card: CardModel,
    pixel: Boolean,
    artSize: Dp,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onPlay: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardArtwork(
                artwork = card.artworkUrl,
                seed = card.uid,
                color = card.color,
                modifier = Modifier.size(artSize),
                cornerRadius = 14.dp,
                pixel = pixel
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(card.emoji + "  " + card.displayName(context), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(card.sourceType.labelRes) + (card.subtitle?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.lastPlayedAt > 0) {
                    Text(
                        text = stringResource(
                            R.string.settings_played_ago,
                            DateUtils.getRelativeTimeSpanString(card.lastPlayedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                extra()
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.editor_send_boop), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.settings_play_now), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

