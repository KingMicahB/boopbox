@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.cardplayer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardplayer.MainViewModel
import com.cardplayer.MoveState
import com.cardplayer.R
import com.cardplayer.billing.PlusReason
import com.cardplayer.ui.PlusBadge
import com.cardplayer.WriteState
import com.cardplayer.audio.Audiobook
import com.cardplayer.data.BoopFile
import com.cardplayer.data.Card
import com.cardplayer.data.Chapter
import com.cardplayer.data.Clip
import com.cardplayer.data.PlaylistItem
import com.cardplayer.data.PodcastFeed
import com.cardplayer.data.PodcastMode
import com.cardplayer.data.SourceType
import com.cardplayer.link.LinkResolver
import com.cardplayer.link.YouTubeChapters
import com.cardplayer.util.MediaScan
import com.cardplayer.yoto.YotoClient
import com.cardplayer.yoto.yotoPrettyTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cardplayer.podcast.RssParser
import com.cardplayer.ui.CardArtwork
import com.cardplayer.ui.theme.CardColorChoices
import com.cardplayer.ui.theme.EmojiChoices
import com.cardplayer.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormat

private enum class Category(@param:StringRes val labelRes: Int, @param:StringRes val hintRes: Int) {
    LINK(R.string.editor_cat_link, R.string.editor_cat_link_hint),
    VIDEO(R.string.editor_cat_video, R.string.editor_cat_video_hint),
    AUDIBLE(R.string.editor_cat_audible, R.string.editor_cat_audible_hint),
    AUDIOBOOK(R.string.editor_cat_book, R.string.editor_cat_book_hint),
    PODCAST(R.string.editor_cat_podcast, R.string.editor_cat_podcast_hint),
    FILE(R.string.source_local_file, R.string.editor_cat_file_hint),
    FOLDER(R.string.source_local_folder, R.string.editor_cat_folder_hint),
    PLAYLIST(R.string.editor_cat_playlist, R.string.editor_cat_playlist_hint),
    YOTO(R.string.source_yoto, R.string.editor_cat_yoto_hint),
    STREAM(R.string.editor_cat_stream, R.string.editor_cat_stream_hint)
}

private fun categoryOf(card: Card): Category = when (card.sourceType) {
    SourceType.SPOTIFY, SourceType.YOUTUBE_MUSIC, SourceType.YOUTUBE, SourceType.APP_LINK -> Category.LINK
    SourceType.VIDEO -> Category.VIDEO
    SourceType.AUDIBLE -> Category.AUDIBLE
    SourceType.PODCAST -> Category.PODCAST
    SourceType.LOCAL_FILE -> if (card.audiobook) Category.AUDIOBOOK else Category.FILE
    SourceType.LOCAL_FOLDER -> if (card.audiobook) Category.AUDIOBOOK else Category.FOLDER
    SourceType.PLAYLIST -> Category.PLAYLIST
    SourceType.YOTO -> Category.YOTO
    SourceType.STREAM_URL -> Category.STREAM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditorScreen(viewModel: MainViewModel, uid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbar.showSnackbar(it.text) } }

    // ---- clipboard: a Paste button next to every link field, plus a nudge when something is waiting.
    val clipboard = LocalClipboard.current
    /** Hands the clipboard's text (trimmed) to [use], if there is any. */
    fun paste(use: (String) -> Unit) {
        scope.launch {
            val clip = try {
                clipboard.getClipEntry()?.clipData
            } catch (_: Exception) {
                null
            }
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            if (!text.isNullOrBlank()) use(text)
        }
    }
    /** Checked without reading the clipboard, so Android shows no "pasted from" toast until Paste is tapped. */
    val clipboardHasText = remember {
        try {
            clipboard.nativeClipboard.hasPrimaryClip()
        } catch (_: Exception) {
            false
        }
    }
    // A card already saved on this phone, or data read off a shared tag (not saved yet).
    val saved = remember(uid) { viewModel.cardRepo.get(uid) }
    val existing = remember(uid) { saved ?: viewModel.consumePrefill(uid) }
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    val moveState by viewModel.moveState.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val plus by viewModel.plus.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: EmojiChoices.first()) }
    var color by remember { mutableStateOf(existing?.color ?: CardColorChoices.first()) }
    var category by remember { mutableStateOf(existing?.let { categoryOf(it) } ?: Category.LINK) }
    var uri by remember { mutableStateOf(existing?.uri ?: "") }
    var artworkUrl by remember { mutableStateOf(existing?.artworkUrl ?: "") }
    var subtitle by remember { mutableStateOf(existing?.subtitle) }
    var podcastMode by remember { mutableStateOf(existing?.podcastMode ?: PodcastMode.LATEST) }
    var episodeUrl by remember { mutableStateOf(existing?.episodeUrl) }
    var episodeTitle by remember { mutableStateOf(existing?.episodeTitle) }
    var resume by remember { mutableStateOf(existing?.resume ?: viewModel.settings.value.defaultResume) }
    var shuffle by remember { mutableStateOf(existing?.shuffle ?: false) }
    var cardRemoved by remember { mutableStateOf(existing?.cardRemoved ?: viewModel.settings.value.cardRemovedMode) }
    var sleepMinutes by remember { mutableStateOf(existing?.sleepMinutes ?: 0) }
    var speed by remember { mutableStateOf(existing?.speed ?: 1f) }
    // Plus: start / end cuts, per track (Card.clips). The main one is kept only for the file / link it was made on.
    var clips by remember { mutableStateOf(existing?.clips ?: emptyMap()) }
    var mainClipUri by remember { mutableStateOf(existing?.uri) }

    // ---- downloaded audiobooks
    /** The book is a folder of chapter files rather than one file. */
    var bookFolder by remember { mutableStateOf(existing?.sourceType == SourceType.LOCAL_FOLDER) }
    /** Chapter marks found inside the chosen file (offered to a "File" card that doesn't use them yet). */
    var fileChapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var readingBook by remember { mutableStateOf(false) }
    /** A file / folder was just picked: what the book scan finds (title, author, cover, chapters) fills the card in. */
    var freshPick by remember { mutableStateOf(false) }
    /** The name / picture the editor filled in by itself, so a better one from the book's tags may replace it. */
    var autoName by remember { mutableStateOf<String?>(null) }
    var autoArt by remember { mutableStateOf<String?>(null) }
    var savedPos by remember(uid) { mutableStateOf(viewModel.savedPosition(uid)) }

    var status by remember { mutableStateOf<String?>(null) }
    var trackArt by remember { mutableStateOf(existing?.trackArt ?: emptyMap()) }
    var playlist by remember { mutableStateOf(existing?.playlist ?: emptyList()) }
    var streamToAdd by remember { mutableStateOf("") }
    var chapters by remember { mutableStateOf(existing?.chapters ?: emptyList()) }
    var scanningChapters by remember { mutableStateOf(false) }
    var scannedChapters by remember { mutableStateOf(existing?.chapters?.isNotEmpty() == true) }
    /** A type change that would throw away what is already chosen, waiting for the grown-up's OK. */
    var pendingCategory by remember { mutableStateOf<Category?>(null) }
    /** The feed address the current [feed] came from, so a retyped address loads afresh. */
    var loadedFeedUrl by remember { mutableStateOf<String?>(null) }
    var episodeQuery by remember { mutableStateOf("") }

    // Export as a .boop: "Save" packs it first, then asks where to put it.
    val boopBusy by viewModel.boopBusy.collectAsStateWithLifecycle()
    var boopToSave by remember { mutableStateOf<java.io.File?>(null) }
    val boopSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { target ->
        val file = boopToSave
        boopToSave = null
        if (target != null && file != null) viewModel.saveBoopTo(file, target)
    }

    // Split one long file / video into songs and trim its start and end (waveform + silence detection + cuts by hand),
    // or trim one song of a playlist. Null = closed; the key is the Card.clips key being edited.
    var songEditor by remember { mutableStateOf<Pair<String, SongSource>?>(null) }
    // Splitting into songs / marking chapters by hand / trimming is Plus; a book's own chapter marks stay free.
    fun openSongEditor(key: String, source: SongSource?) {
        when {
            source == null -> Unit
            !plus -> viewModel.showPaywall(if (key == Card.MAIN_TRACK) PlusReason.SPLIT else PlusReason.TRIM)
            else -> songEditor = key to source
        }
    }

    fun scanChapters(link: String) {
        scope.launch {
            scanningChapters = true
            val scan = YouTubeChapters.scan(link)
            chapters = scan?.chapters ?: emptyList()
            if (name.isBlank() && scan?.videoTitle != null) name = scan.videoTitle
            scanningChapters = false
            scannedChapters = true
        }
    }
    var artworkCredit by remember { mutableStateOf(existing?.artworkCredit) }
    /** null = picker closed, "" = picking the card picture, otherwise the track uri being picked for. */
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    var folderTracks by remember { mutableStateOf<List<MediaScan.Track>>(emptyList()) }
    LaunchedEffect(uri, category, bookFolder) {
        folderTracks = if ((category == Category.FOLDER || (category == Category.AUDIOBOOK && bookFolder)) && uri.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    MediaScan.folderTracks(context, uri)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        } else emptyList()
    }

    /** Fills in what a freshly picked book says about itself, without overwriting what the grown-up typed or chose. */
    fun applyBook(title: String?, author: String?, cover: String?) {
        if (title != null && (name.isBlank() || name == autoName)) {
            name = title
            autoName = title
        }
        if (author != null) subtitle = author
        if (cover != null && (artworkUrl.isBlank() || artworkUrl == autoArt)) {
            artworkUrl = cover
            autoArt = cover
            artworkCredit = null
        }
    }

    // One file (a "File" card or a one-file audiobook): read its own chapter marks, title, author and cover.
    LaunchedEffect(uri, category, bookFolder) {
        fileChapters = emptyList()
        val book = category == Category.AUDIOBOOK && !bookFolder
        if (uri.isBlank() || !(book || category == Category.FILE)) return@LaunchedEffect
        val fresh = freshPick
        freshPick = false
        readingBook = true
        val info = withContext(Dispatchers.IO) {
            try {
                Audiobook.readFile(context, Uri.parse(uri)) { n -> context.getString(R.string.book_chapter_number, n) }
            } catch (_: Exception) {
                null
            }
        }
        readingBook = false
        if (info == null) return@LaunchedEffect
        fileChapters = info.chapters
        if (!fresh) return@LaunchedEffect
        if (book) {
            if (info.chapters.isNotEmpty()) chapters = info.chapters
            applyBook(info.title, info.author, info.cover?.let { withContext(Dispatchers.IO) { Audiobook.saveCover(context, it) } })
        } else if (chapters.isEmpty()) {
            // A "File" card: chapter marks the file brings become its songs straight away.
            chapters = info.chapters
        }
        if (info.chapters.isNotEmpty()) scannedChapters = true
    }

    // A freshly picked book folder: the first file's tags name the book; a picture in the folder is the cover.
    // (Keyed on the type too: a "Folder" card switched to "Audiobook" keeps the same track list.)
    LaunchedEffect(folderTracks, category, bookFolder) {
        if (category != Category.AUDIOBOOK || !bookFolder || !freshPick || folderTracks.isEmpty()) return@LaunchedEffect
        freshPick = false
        readingBook = true
        val tracks = folderTracks
        val folder = uri
        val (info, cover) = withContext(Dispatchers.IO) {
            val info = try {
                Audiobook.readFolder(context, tracks)
            } catch (_: Exception) {
                null
            }
            info to (MediaScan.folderCover(context, folder) ?: info?.cover?.let { Audiobook.saveCover(context, it) })
        }
        readingBook = false
        applyBook(info?.title, info?.author, cover)
    }

    var feed by remember { mutableStateOf<PodcastFeed?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pickedLabel by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            try {
                context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            uri = picked.toString()
            chapters = emptyList()
            scannedChapters = false
            freshPick = true
            if (category == Category.AUDIOBOOK) bookFolder = false
            val doc = DocumentFile.fromSingleUri(context, picked)
            pickedLabel = doc?.name
            if (name.isBlank()) {
                name = doc?.name?.substringBeforeLast('.') ?: ""
                autoName = name
            }
            if (resume.not() && (category == Category.AUDIOBOOK || doc?.name?.endsWith(".m4b", true) == true)) resume = true
        }
    }
    val songsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val added = picked.mapNotNull { u ->
            try {
                context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            val s = u.toString()
            if (playlist.any { it.uri == s }) null
            else PlaylistItem(s, DocumentFile.fromSingleUri(context, u)?.name?.substringBeforeLast('.') ?: context.getString(R.string.editor_default_song_title))
        }
        playlist = playlist + added
        if (uri.isBlank()) uri = "playlist:$uid"
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            try {
                context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            uri = picked.toString()
            if (category == Category.AUDIOBOOK) {
                freshPick = true
                bookFolder = true
                chapters = emptyList()
                resume = true
            }
            val doc = DocumentFile.fromTreeUri(context, picked)
            pickedLabel = doc?.name
            if (name.isBlank()) {
                name = doc?.name ?: ""
                autoName = name
            }
        }
    }

    val linkType = remember(uri, category, bookFolder) {
        when (category) {
            Category.LINK -> LinkResolver.detect(uri).let { if (it == SourceType.STREAM_URL) SourceType.APP_LINK else it }
            Category.AUDIBLE -> SourceType.AUDIBLE
            Category.VIDEO -> SourceType.VIDEO
            Category.AUDIOBOOK -> if (bookFolder) SourceType.LOCAL_FOLDER else SourceType.LOCAL_FILE
            Category.PODCAST -> SourceType.PODCAST
            Category.FILE -> SourceType.LOCAL_FILE
            Category.FOLDER -> SourceType.LOCAL_FOLDER
            Category.PLAYLIST -> SourceType.PLAYLIST
            Category.YOTO -> SourceType.YOTO
            Category.STREAM -> SourceType.STREAM_URL
        }
    }

    // ---- Yoto cards: account state, library picker, chapters (for per-chapter pictures).
    val yotoTokens by viewModel.player.yoto.tokens.collectAsStateWithLifecycle()
    val yotoLogin by viewModel.player.yoto.loginState.collectAsStateWithLifecycle()
    var showYotoPicker by remember { mutableStateOf(false) }
    var yotoCards by remember { mutableStateOf<List<YotoClient.LibraryCard>?>(null) }
    var yotoChapters by remember { mutableStateOf<List<YotoClient.Chapter>>(emptyList()) }
    LaunchedEffect(uri, category, yotoTokens != null) {
        yotoChapters = emptyList()
        if (category == Category.YOTO && uri.isNotBlank() && (yotoTokens != null || YotoClient.isCardLink(uri))) {
            try {
                val detail = viewModel.player.yoto.card(uri)
                if (detail != null) {
                    yotoChapters = detail.chapters
                    if (name.isBlank()) name = detail.title
                    if (artworkUrl.isBlank() && detail.coverUrl != null) artworkUrl = detail.coverUrl
                    if (subtitle == null) subtitle = detail.category
                }
            } catch (e: Exception) {
                status = e.message
            }
        }
    }
    LaunchedEffect(showYotoPicker) {
        if (showYotoPicker && yotoCards == null) {
            yotoCards = try {
                viewModel.player.yoto.library()
            } catch (e: Exception) {
                status = e.message
                emptyList()
            }
        }
    }

    // Auto-fetch title + artwork a moment after a link is pasted.
    LaunchedEffect(uri, category) {
        if (category != Category.LINK && category != Category.AUDIBLE && category != Category.VIDEO) return@LaunchedEffect
        val link = uri.trim()
        if (link.length < 8 || (!link.startsWith("http") && !link.contains(":"))) return@LaunchedEffect
        if (link == existing?.uri && artworkUrl.isNotBlank()) return@LaunchedEffect
        delay(700)
        fetching = true
        // Playlist without a video? Resolve its first video so the link autoplays.
        val playable = LinkResolver.resolvePlayableLink(linkType, link)
        if (playable != link) {
            uri = playable
            fetching = false
            return@LaunchedEffect // the uri change re-runs this effect with the playable link
        }
        val meta = LinkResolver.fetchMetadata(linkType, link)
        fetching = false
        if (meta.title != null && name.isBlank()) name = meta.title
        if (meta.artworkUrl != null) artworkUrl = meta.artworkUrl
        if (meta.author != null) subtitle = meta.author
        status = if (meta.title == null) context.getString(R.string.editor_status_no_details) else null
        // Long YouTube videos often hold a whole album: look for a tracklist.
        val isVideo = linkType == SourceType.YOUTUBE || linkType == SourceType.YOUTUBE_MUSIC
        if (isVideo && !(link == existing?.uri && chapters.isNotEmpty())) scanChapters(link)
        if (!isVideo) chapters = emptyList()
    }

    fun loadFeed() {
        val link = uri.trim()
        if (link.isBlank()) return
        scope.launch {
            fetching = true
            status = null
            try {
                val f = RssParser.fetch(link)
                feed = f
                loadedFeedUrl = link
                if (name.isBlank()) name = f.title
                if (f.artworkUrl != null && artworkUrl.isBlank()) artworkUrl = f.artworkUrl
                subtitle = f.title
                if (podcastMode == PodcastMode.LATEST) {
                    episodeUrl = f.episodes.firstOrNull()?.url
                    episodeTitle = f.episodes.firstOrNull()?.title
                }
                if (f.episodes.isEmpty()) status = context.getString(R.string.editor_status_feed_empty)
            } catch (e: Exception) {
                status = context.getString(R.string.editor_status_feed_failed, e.message)
            }
            fetching = false
        }
    }

    // Podcast: the feed loads by itself a moment after its address is pasted, like links fetch their details.
    LaunchedEffect(uri, category) {
        if (category != Category.PODCAST) return@LaunchedEffect
        val link = uri.trim()
        if (!link.startsWith("http") || link.length < 12 || link == loadedFeedUrl) return@LaunchedEffect
        delay(700)
        loadFeed()
    }

    /** Switches the card type; [keep] = the chosen file / folder carries over (File / Folder <-> Audiobook). */
    fun applyCategory(c: Category, keep: Boolean) {
        if (c == Category.AUDIOBOOK) {
            if (keep) bookFolder = category == Category.FOLDER
            freshPick = keep
            resume = true
        }
        category = c
        if (!keep) {
            uri = ""
            feed = null
            loadedFeedUrl = null
            chapters = emptyList()
            scannedChapters = false
            pickedLabel = null
            playlist = emptyList()
            trackArt = emptyMap()
        }
        status = null
    }

    val isYouTube = linkType == SourceType.YOUTUBE || linkType == SourceType.YOUTUBE_MUSIC
    val ytCache = viewModel.player.youtubeCache
    val ytVersion by ytCache.version.collectAsStateWithLifecycle()
    /** A YouTube playlist card's downloaded songs (sideload build), for a picture and a cut each. */
    val ytListTracks = remember(ytVersion, uri, linkType) {
        if (isYouTube && !LinkResolver.isSingleTrack(linkType, uri.trim())) ytCache.localTracks(Card(uid = uid, sourceType = linkType, uri = uri.trim())) else null
    }

    /**
     * What the split & trim editor works on for the card's one file / video: the file, a YouTube video's downloaded
     * audio, or (not downloaded, or the Play build) the video itself through YouTube's player. Null = nothing to edit.
     */
    fun mainSongSource(): SongSource? = when {
        uri.isBlank() -> null
        isYouTube -> {
            if (!LinkResolver.isSingleTrack(linkType, uri.trim())) null
            else ytCache.localTracks(Card(uid = uid, sourceType = linkType, uri = uri.trim()))?.singleOrNull()
                ?.let { SongSource.File(android.net.Uri.fromFile(it.file).toString()) }
                ?: YouTubeChapters.videoId(uri.trim())?.let { SongSource.YouTube(it) }
        }
        linkType == SourceType.LOCAL_FILE -> SongSource.File(uri)
        else -> null
    }
    val openSplitEditor: () -> Unit = { openSongEditor(Card.MAIN_TRACK, mainSongSource()) }

    /** The card's main cut, when it was made on the file / link chosen now. */
    val mainClip = clips[Card.MAIN_TRACK]?.takeIf { mainClipUri == uri.trim() && it.isSet }

    fun buildCard(): Card? {
        if (category == Category.PLAYLIST) {
            if (playlist.isEmpty()) {
                status = context.getString(R.string.editor_status_playlist_empty)
                return null
            }
            if (uri.isBlank()) uri = "playlist:$uid"
        } else if (uri.isBlank()) {
            status = context.getString(R.string.editor_status_choose_source)
            return null
        }
        if (category == Category.STREAM && LinkResolver.detect(uri).isExternal) {
            status = context.getString(R.string.editor_status_not_stream, context.getString(LinkResolver.detect(uri).labelRes))
            return null
        }
        return Card(
            uid = uid,
            name = name.trim(),
            emoji = emoji,
            color = color,
            sourceType = linkType,
            uri = uri.trim(),
            artworkUrl = artworkUrl.trim().ifBlank { null },
            subtitle = subtitle,
            podcastMode = podcastMode,
            episodeUrl = episodeUrl,
            episodeTitle = episodeTitle,
            resume = resume && !linkType.isExternal,
            shuffle = shuffle && category != Category.AUDIOBOOK &&
                (linkType == SourceType.LOCAL_FOLDER || linkType == SourceType.PODCAST || linkType == SourceType.PLAYLIST),
            trackArt = trackArt,
            playlist = if (category == Category.PLAYLIST) playlist else emptyList(),
            chapters = if (linkType == SourceType.YOUTUBE || linkType == SourceType.YOUTUBE_MUSIC || linkType == SourceType.LOCAL_FILE) chapters else emptyList(),
            artworkCredit = artworkCredit,
            cardRemoved = cardRemoved,
            sleepMinutes = sleepMinutes,
            // An audiobook folder that arrived as a .boop is a playlist, and stays a book.
            audiobook = category == Category.AUDIOBOOK || (category == Category.PLAYLIST && existing?.audiobook == true),
            speed = if (linkType.isExternal) 1f else speed,
            // Cuts only for what the card still plays: its one file / video, or songs still in its list.
            clips = clips.filter { (key, clip) ->
                clip.isSet && when {
                    key == Card.MAIN_TRACK -> mainClipUri == uri.trim() &&
                        (linkType == SourceType.LOCAL_FILE || (isYouTube && LinkResolver.isSingleTrack(linkType, uri.trim())))
                    category == Category.PLAYLIST -> playlist.any { it.uri == key }
                    isYouTube -> ytListTracks?.any { it.id == key } ?: true
                    else -> false
                }
            },
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
    }

    /** [buildCard], but a refusal also pops up next to the Save bar; the inline reason may be scrolled far off screen. */
    fun buildCardOrComplain(): Card? = buildCard() ?: run {
        status?.let { reason -> scope.launch { snackbar.showSnackbar(reason) } }
        null
    }

    // ---- unsaved-changes guard: everything the editor can change, compared with how it started.
    fun snapshot(): List<Any?> = listOf(
        name, emoji, color, category, uri, artworkUrl, subtitle, podcastMode, episodeUrl, episodeTitle,
        resume, shuffle, trackArt, playlist, chapters, artworkCredit, cardRemoved, sleepMinutes, speed, bookFolder
    )
    val initialSnapshot = remember(uid) { snapshot() }
    val dirty = snapshot() != initialSnapshot || (saved == null && uri.isNotBlank())
    var showLeaveDialog by remember { mutableStateOf(false) }
    fun leave() {
        if (dirty) showLeaveDialog = true else viewModel.back()
    }
    BackHandler { leave() }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.editor_leave_title)) },
            text = { Text(stringResource(if (saved == null) R.string.editor_leave_unsaved else R.string.editor_leave_changed)) },
            confirmButton = {
                TextButton(onClick = {
                    val card = buildCardOrComplain()
                    if (card != null) {
                        viewModel.saveCard(card)
                        showLeaveDialog = false
                        viewModel.back()
                    } else {
                        showLeaveDialog = false // the snackbar and status text explain what is missing
                    }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showLeaveDialog = false
                        viewModel.back()
                    }) { Text(stringResource(R.string.editor_leave_discard)) }
                    TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.editor_leave_keep)) }
                }
            }
        )
    }

    pendingCategory?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingCategory = null },
            title = { Text(stringResource(R.string.editor_type_change_title)) },
            text = { Text(stringResource(R.string.editor_type_change_body, stringResource(c.labelRes))) },
            confirmButton = {
                TextButton(onClick = {
                    applyCategory(c, keep = false)
                    pendingCategory = null
                }) { Text(stringResource(R.string.editor_type_change_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingCategory = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    /** Quick share: packs the card as a .boop and opens the share sheet (WhatsApp, email, Drive…). */
    val sendBoop: () -> Unit = {
        buildCardOrComplain()?.let { card ->
            viewModel.exportBoop(card) { file -> context.startActivity(BoopFile.shareIntent(context, file)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (saved == null) R.string.editor_title_new else R.string.editor_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = sendBoop, enabled = !boopBusy) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.editor_send_boop))
                    }
                    if (saved != null) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.editor_delete_card_cd))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Save lives in a bar that never scrolls away: the editor is long.
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Button(
                        onClick = {
                            buildCardOrComplain()?.let {
                                viewModel.saveCard(it)
                                viewModel.back()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            buildCardOrComplain()?.let {
                                viewModel.saveCard(it)
                                viewModel.playNow(it)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_save_and_play))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Preview
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardArtwork(
                    artwork = artworkUrl.ifBlank { null },
                    seed = uid,
                    color = color,
                    modifier = Modifier.size(96.dp),
                    cornerRadius = 20.dp,
                    pixel = appSettings.pixelArt
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // The name lives next to the picture it belongs with, not far down under "Looks".
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.editor_name)) },
                        placeholder = { Text(stringResource(R.string.editor_untitled)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.editor_card_id, uid), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saved == null && existing != null) {
                        Text(stringResource(R.string.editor_read_from_shared), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Each section is its own composable lambda, so it compiles to its own method: as one huge method D8 mis-allocated
            // registers and ART rejected CardEditorScreenKt (VerifyError) in release builds.
            val playSection: @Composable ColumnScope.() -> Unit = {
                SectionTitle(stringResource(R.string.editor_section_play))
                var typeMenuOpen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = typeMenuOpen,
                    onExpandedChange = { typeMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = stringResource(category.labelRes),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.editor_card_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false }
                    ) {
                        Category.entries.forEach { c ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(c.labelRes), style = MaterialTheme.typography.bodyLarge)
                                        Text(stringResource(c.hintRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = if (category == c) {
                                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                                } else {
                                    { Spacer(Modifier.size(18.dp)) }
                                },
                                onClick = {
                                    typeMenuOpen = false
                                    if (category != c) {
                                        // A file or folder already chosen carries over between "File" / "Folder" and "Audiobook".
                                        val keep = uri.isNotBlank() && when {
                                            c == Category.AUDIOBOOK -> category == Category.FILE || category == Category.FOLDER
                                            category == Category.AUDIOBOOK -> c == (if (bookFolder) Category.FOLDER else Category.FILE)
                                            else -> false
                                        }
                                        // Switching away from something already chosen throws it away: ask first.
                                        if (!keep && (uri.isNotBlank() || playlist.isNotEmpty())) pendingCategory = c
                                        else applyCategory(c, keep)
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
                Text(stringResource(category.hintRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                when (category) {
                    Category.LINK, Category.AUDIBLE, Category.VIDEO, Category.STREAM -> {
                        OutlinedTextField(
                            value = uri,
                            onValueChange = { uri = it },
                            label = {
                                Text(
                                    stringResource(
                                        when (category) {
                                            Category.LINK -> R.string.editor_paste_link
                                            Category.AUDIBLE -> R.string.editor_paste_audible
                                            Category.VIDEO -> R.string.editor_paste_video
                                            else -> R.string.editor_stream_url
                                        }
                                    )
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            trailingIcon = {
                                TextButton(onClick = {
                                    paste { text ->
                                        uri = text
                                        // An Audible link pasted into the music field is still an Audible card.
                                        if (category == Category.LINK && LinkResolver.detect(text) == SourceType.AUDIBLE) category = Category.AUDIBLE
                                        // Same for a Netflix / Disney+ link: it is a video card.
                                        if (category == Category.LINK && LinkResolver.detect(text) == SourceType.VIDEO) category = Category.VIDEO
                                    }
                                }) { Text(stringResource(R.string.editor_paste)) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uri.isBlank() && clipboardHasText) {
                            Text(stringResource(R.string.editor_clipboard_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (category == Category.LINK && uri.isNotBlank()) {
                            val appName = when (linkType) {
                                SourceType.SPOTIFY -> R.string.source_spotify
                                SourceType.YOUTUBE_MUSIC -> R.string.source_youtube_music
                                SourceType.YOUTUBE -> R.string.source_youtube
                                else -> null
                            }
                            // YouTube cards say where they play in their download status below.
                            if (linkType != SourceType.YOUTUBE && linkType != SourceType.YOUTUBE_MUSIC) {
                                Text(
                                    if (appName != null) stringResource(R.string.editor_will_open_in, stringResource(appName))
                                    else stringResource(R.string.editor_will_open_in_other),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (linkType == SourceType.YOUTUBE || linkType == SourceType.YOUTUBE_MUSIC) {
                                val draft = Card(uid = uid, sourceType = linkType, uri = uri.trim())
                                YouTubeCacheStatus(
                                    cache = viewModel.player.youtubeCache,
                                    card = draft,
                                    autoCache = appSettings.youtubeAutoCache,
                                    onDownload = {
                                        scope.launch {
                                            try {
                                                viewModel.player.youtubeCache.ensure(draft)
                                            } catch (e: Exception) {
                                                status = e.message
                                            }
                                        }
                                    }
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            scanningChapters -> stringResource(R.string.editor_scanning_tracklist)
                                            chapters.size > 1 -> stringResource(R.string.editor_found_songs, chapters.size)
                                            scannedChapters -> stringResource(R.string.editor_no_tracklist)
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { scanChapters(uri) }, enabled = !scanningChapters) { Text(stringResource(R.string.editor_rescan)) }
                                }
                                if (chapters.size > 1) {
                                    Text(
                                        stringResource(R.string.editor_skip_needs_access),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Split into songs and / or trim the start and end (Plus). A video that isn't downloaded
                                // (always, in the Play build) is listened to through YouTube's own player in the editor.
                                if (LinkResolver.isSingleTrack(linkType, uri.trim())) {
                                    if (mainClip == null) {
                                        Text(stringResource(R.string.editor_trim_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MainClipLine(mainClip) { clips = clips - Card.MAIN_TRACK }
                                    OutlinedButton(onClick = openSplitEditor) {
                                        Text(stringResource(if (chapters.size > 1) R.string.editor_edit_songs else R.string.editor_split_into_songs))
                                    }
                                }
                            }
                        }
                        if (category == Category.VIDEO && uri.isNotBlank()) {
                            val app = LinkResolver.videoApp(uri)
                            Text(
                                if (app != null) stringResource(R.string.editor_will_open_in, stringResource(app.second))
                                else stringResource(R.string.editor_video_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (app != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.editor_video_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (category == Category.AUDIBLE) {
                            Text(
                                stringResource(R.string.editor_audible_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Category.PODCAST -> {
                        OutlinedTextField(
                            value = uri,
                            onValueChange = { uri = it },
                            label = { Text(stringResource(R.string.editor_rss_address)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            trailingIcon = {
                                TextButton(onClick = { paste { text -> uri = text } }) { Text(stringResource(R.string.editor_paste)) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uri.isBlank() && clipboardHasText) {
                            Text(stringResource(R.string.editor_clipboard_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { loadFeed() }, enabled = uri.isNotBlank() && !fetching) {
                                Text(stringResource(if (feed == null) R.string.editor_load_feed else R.string.editor_reload))
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = uri.isNotBlank(),
                                onClick = {
                                    val temp = Card(uid = uid, sourceType = SourceType.PODCAST, uri = uri.trim(), podcastMode = podcastMode, episodeUrl = episodeUrl)
                                    scope.launch {
                                        status = context.getString(R.string.editor_status_downloading_newest)
                                        val f = viewModel.player.podcastCache.refresh(temp, download = true)
                                        status = if (f == null) {
                                            context.getString(R.string.editor_status_feed_unreachable)
                                        } else {
                                            context.getString(
                                                R.string.editor_status_downloaded,
                                                viewModel.player.podcastCache.targetEpisode(temp, f)?.title ?: context.getString(R.string.editor_newest_episode_fallback)
                                            )
                                        }
                                    }
                                }
                            ) { Text(stringResource(R.string.editor_download_newest)) }
                            Spacer(Modifier.width(12.dp))
                            feed?.let { Text(pluralStringResource(R.plurals.editor_feed_summary, it.episodes.size, it.title, it.episodes.size), style = MaterialTheme.typography.bodyMedium) }
                        }
                        PodcastMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        podcastMode = mode
                                        if (mode == PodcastMode.LATEST) {
                                            episodeUrl = feed?.episodes?.firstOrNull()?.url ?: episodeUrl
                                            episodeTitle = feed?.episodes?.firstOrNull()?.title ?: episodeTitle
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = podcastMode == mode, onClick = null)
                                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (podcastMode == PodcastMode.EPISODE) {
                            val episodes = feed?.episodes
                            if (episodes == null) {
                                Text(
                                    episodeTitle?.let { stringResource(R.string.editor_episode_chosen, it) } ?: stringResource(R.string.editor_episode_load_feed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                // Long feeds: a search box, so an episode from years ago is a few letters away.
                                if (episodes.size > 10) {
                                    OutlinedTextField(
                                        value = episodeQuery,
                                        onValueChange = { episodeQuery = it },
                                        label = { Text(stringResource(R.string.editor_episode_search)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                val wanted = episodeQuery.trim()
                                val shown = episodes.filter { wanted.isEmpty() || it.title.contains(wanted, ignoreCase = true) }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(8.dp)
                                ) {
                                    if (shown.isEmpty()) {
                                        Text(stringResource(R.string.editor_episode_none_match), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    shown.take(60).forEach { ep ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    episodeUrl = ep.url
                                                    episodeTitle = ep.title
                                                }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = episodeUrl == ep.url, onClick = null)
                                            Column {
                                                Text(ep.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                                val meta = listOfNotNull(ep.pubDate?.take(16), ep.durationText).joinToString(" · ")
                                                if (meta.isNotBlank()) {
                                                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Category.FILE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.editor_choose_file)) }
                            Spacer(Modifier.width(12.dp))
                            Text(pickedLabel ?: stringResource(if (uri.isNotBlank()) R.string.editor_file_chosen else R.string.editor_no_file), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                        if (uri.isNotBlank()) {
                            Text(stringResource(R.string.editor_songs_inside), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (chapters.size > 1) stringResource(R.string.editor_split_done, chapters.size)
                                else stringResource(R.string.editor_split_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MainClipLine(mainClip) { clips = clips - Card.MAIN_TRACK }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = openSplitEditor) {
                                    Text(stringResource(if (chapters.size > 1) R.string.editor_edit_songs else R.string.editor_split_into_songs))
                                }
                                if (chapters.isNotEmpty()) {
                                    TextButton(onClick = { chapters = emptyList() }) { Text(stringResource(R.string.editor_remove_split)) }
                                }
                            }
                            if (chapters.isEmpty() && fileChapters.size > 1) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        pluralStringResource(R.plurals.editor_file_has_chapters, fileChapters.size, fileChapters.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        chapters = fileChapters
                                        scannedChapters = true
                                    }) { Text(stringResource(R.string.editor_use_file_chapters)) }
                                }
                            }
                        }
                    }
                    Category.AUDIOBOOK -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.editor_book_choose_file))
                            }
                            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.editor_book_choose_folder))
                            }
                        }
                        Text(
                            stringResource(R.string.editor_book_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uri.isNotBlank()) {
                            Text(
                                pickedLabel ?: stringResource(if (bookFolder) R.string.editor_folder_chosen else R.string.editor_file_chosen),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (readingBook) {
                                    CircularProgressIndicator(Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    when {
                                        readingBook -> stringResource(R.string.editor_book_reading)
                                        bookFolder -> pluralStringResource(R.plurals.editor_book_parts, folderTracks.size, folderTracks.size)
                                        chapters.size > 1 -> pluralStringResource(R.plurals.editor_book_chapters, chapters.size, chapters.size)
                                        else -> stringResource(R.string.editor_book_no_chapters)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!bookFolder && !readingBook) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = openSplitEditor) {
                                        Text(stringResource(if (chapters.size > 1) R.string.editor_book_edit_chapters else R.string.editor_book_mark_chapters))
                                    }
                                    // Hand-made chapters can always go back to the ones the file came with.
                                    if (fileChapters.size > 1 && chapters != fileChapters) {
                                        TextButton(onClick = { chapters = fileChapters }) { Text(stringResource(R.string.editor_use_file_chapters)) }
                                    }
                                }
                            }
                            // Where the book stopped (saved card, "continue where it left off"), with a way back to the start.
                            val pos = savedPos
                            val where: String? = when {
                                pos == null || saved == null -> null
                                bookFolder -> folderTracks.indexOfFirst { it.uri == pos.mediaId }.takeIf { it >= 0 }?.let { i ->
                                    stringResource(R.string.editor_book_at_part, i + 1, folderTracks.size, folderTracks[i].title, YouTubeChapters.formatTime(pos.positionMs))
                                }
                                pos.mediaId != uri -> null
                                chapters.size > 1 -> {
                                    val i = chapters.indexOfLast { it.startMs <= pos.positionMs }.coerceAtLeast(0)
                                    stringResource(
                                        R.string.editor_book_at_chapter, i + 1, chapters.size, chapters[i].title,
                                        YouTubeChapters.formatTime(pos.positionMs - chapters[i].startMs)
                                    )
                                }
                                else -> stringResource(R.string.editor_book_at_time, YouTubeChapters.formatTime(pos.positionMs))
                            }
                            if (where != null) {
                                Text(stringResource(R.string.editor_book_progress_title), style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(where, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        viewModel.clearPosition(uid)
                                        savedPos = null
                                    }) { Text(stringResource(R.string.editor_book_start_over)) }
                                }
                            }
                        }
                    }
                    Category.FOLDER -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.editor_choose_folder)) }
                            Spacer(Modifier.width(12.dp))
                            // "<folder> · 12 tracks": what the folder holds, right where it was picked.
                            Text(
                                (pickedLabel ?: stringResource(if (uri.isNotBlank()) R.string.editor_folder_chosen else R.string.editor_no_folder)) +
                                    if (folderTracks.isNotEmpty()) " · " + pluralStringResource(R.plurals.editor_folder_track_count, folderTracks.size, folderTracks.size) else "",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        }
                    }
                    Category.YOTO -> {
                        val physicalYoto = YotoClient.isCardLink(uri)
                        if (yotoTokens == null && !physicalYoto) {
                            Text(
                                stringResource(R.string.editor_yoto_signin_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { viewModel.player.yoto.startLogin() }, enabled = yotoLogin != YotoClient.LoginState.Waiting) {
                                    Text(stringResource(if (yotoLogin == YotoClient.LoginState.Waiting) R.string.editor_yoto_waiting else R.string.editor_yoto_connect))
                                }
                                if (yotoLogin == YotoClient.LoginState.Waiting) {
                                    TextButton(onClick = { viewModel.player.yoto.cancelLogin() }) { Text(stringResource(R.string.common_cancel)) }
                                }
                            }
                            (yotoLogin as? YotoClient.LoginState.Error)?.let {
                                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (yotoTokens != null) {
                                    OutlinedButton(onClick = { showYotoPicker = true }) { Text(stringResource(if (uri.isBlank()) R.string.editor_yoto_choose else R.string.editor_yoto_change)) }
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text(
                                    when {
                                        uri.isBlank() -> stringResource(R.string.editor_yoto_none)
                                        physicalYoto -> stringResource(R.string.editor_yoto_real_card, name.ifBlank { stringResource(R.string.editor_yoto_plays_from_card) })
                                        else -> name.ifBlank { stringResource(R.string.source_yoto) }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (yotoChapters.isNotEmpty()) {
                                Text(
                                    pluralStringResource(R.plurals.editor_yoto_chapters_note, yotoChapters.size, yotoChapters.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val downloading by viewModel.player.podcastCache.downloading.collectAsStateWithLifecycle()
                                val cacheVersion by viewModel.player.podcastCache.version.collectAsStateWithLifecycle()
                                val group = "yoto:$uri"
                                val progress = downloading[group]
                                val haveCount = remember(cacheVersion, yotoChapters) {
                                    yotoChapters.sumOf { ch -> ch.tracks.count { t -> viewModel.player.podcastCache.cachedFileKeyed(group, ch.key + "/" + t.key) != null } }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        enabled = progress == null,
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val detail = viewModel.player.yoto.card(uri)
                                                    if (detail != null) viewModel.player.downloadYoto(Card(uid = uid, sourceType = SourceType.YOTO, uri = uri), detail)
                                                } catch (e: Exception) {
                                                    status = e.message
                                                }
                                            }
                                        }
                                    ) { Text(if (progress != null) stringResource(R.string.editor_downloading_percent, (progress * 100).toInt()) else stringResource(R.string.editor_download_offline)) }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        if (haveCount == 0) stringResource(R.string.editor_nothing_downloaded) else pluralStringResource(R.plurals.editor_tracks_on_phone, haveCount, haveCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Category.PLAYLIST -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { songsPicker.launch(arrayOf("audio/*", "video/*", "*/*")) }) { Text(stringResource(R.string.editor_add_songs)) }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (playlist.isEmpty()) stringResource(R.string.editor_no_songs) else pluralStringResource(R.plurals.editor_song_count, playlist.size, playlist.size),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = streamToAdd,
                                onValueChange = { streamToAdd = it },
                                label = { Text(stringResource(R.string.editor_add_stream_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = streamToAdd.trim().startsWith("http"),
                                onClick = {
                                    val s = streamToAdd.trim()
                                    val detected = LinkResolver.detect(s)
                                    if (detected.isExternal) {
                                        status = context.getString(R.string.editor_status_playlist_external, context.getString(detected.labelRes))
                                    } else {
                                        status = null
                                        if (playlist.none { it.uri == s }) {
                                            val title = s.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').ifBlank { Uri.parse(s).host ?: context.getString(R.string.editor_default_stream_title) }
                                            playlist = playlist + PlaylistItem(s, title)
                                        }
                                        streamToAdd = ""
                                        if (uri.isBlank()) uri = "playlist:$uid"
                                    }
                                }
                            ) { Text(stringResource(R.string.common_add)) }
                        }
                        if (playlist.isNotEmpty()) {
                            Text(
                                stringResource(R.string.editor_playlist_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (fetching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.editor_fetching), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                if (!linkType.isExternal) {
                    Text(stringResource(R.string.editor_tap_again_title), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        true to stringResource(R.string.editor_resume_continue),
                        false to stringResource(R.string.editor_resume_restart)
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { resume = value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = resume == value, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (!linkType.isExternal) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_speed_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        if (!plus) PlusBadge(Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.editor_speed_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f).forEach { s ->
                            FilterChip(
                                selected = speed == s,
                                onClick = { if (plus || s == 1f) speed = s else viewModel.showPaywall(PlusReason.SPEED) },
                                label = { Text(if (s == 1f) stringResource(R.string.editor_speed_normal) else DecimalFormat("0.##").format(s) + "×") }
                            )
                        }
                    }
                }
                if (category != Category.AUDIOBOOK && (linkType == SourceType.LOCAL_FOLDER || linkType == SourceType.PODCAST || linkType == SourceType.PLAYLIST)) {
                    SwitchLine(stringResource(R.string.editor_shuffle), null, shuffle) { shuffle = it }
                }
                Text(stringResource(R.string.editor_lifted_title), style = MaterialTheme.typography.titleMedium)
                listOf(
                    "stop" to stringResource(R.string.editor_lifted_stop),
                    "pause" to stringResource(R.string.editor_lifted_pause),
                    "nothing" to stringResource(R.string.editor_lifted_nothing)
                ).forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { cardRemoved = value },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = cardRemoved == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_sleep_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (!plus) PlusBadge(Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.editor_sleep_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 45, 60).forEach { mins ->
                        FilterChip(
                            selected = sleepMinutes == mins,
                            onClick = { if (plus || mins == 0) sleepMinutes = mins else viewModel.showPaywall(PlusReason.CARD_SLEEP) },
                            label = { Text(if (mins == 0) stringResource(R.string.editor_sleep_off) else "$mins") }
                        )
                    }
                }
            }
            playSection()

            val looksSection: @Composable ColumnScope.() -> Unit = {
                SectionTitle(stringResource(R.string.editor_section_looks))
                Text(stringResource(R.string.editor_picture), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.editor_picture_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { pickerTarget = "" }) { Text(stringResource(if (artworkUrl.isBlank()) R.string.editor_find_picture else R.string.editor_change_picture)) }
                    Spacer(Modifier.width(8.dp))
                    if (artworkUrl.isNotBlank()) {
                        TextButton(onClick = {
                            artworkUrl = ""
                            artworkCredit = null
                        }) { Text(stringResource(R.string.common_remove)) }
                    }
                }
                artworkCredit?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val tracks: List<Pair<String, String>> = when (category) {
                    Category.FOLDER -> folderTracks.map { it.uri to it.title }
                    Category.AUDIOBOOK -> if (bookFolder) folderTracks.map { it.uri to it.title }
                    else chapters.map { it.artKey to "${YouTubeChapters.formatTime(it.startMs)}  ${it.title}" }
                    Category.PODCAST -> feed?.episodes?.map { it.url to it.title } ?: emptyList()
                    Category.PLAYLIST -> playlist.map { it.uri to it.title }
                    // A downloaded YouTube playlist lists its songs (picture and cut each, keyed by video id).
                    Category.LINK -> ytListTracks?.map { it.id to it.title }
                        ?: chapters.map { it.artKey to "${YouTubeChapters.formatTime(it.startMs)}  ${it.title}" }
                    Category.FILE -> chapters.map { it.artKey to "${YouTubeChapters.formatTime(it.startMs)}  ${it.title}" }
                    Category.YOTO -> yotoChapters.map { it.key to yotoPrettyTitle(it.title) }
                    else -> emptyList()
                }
                // Yoto chapters come with their own pixel icon; it is the picture unless the grown-up picks another.
                // An audiobook's chapters show the book's cover unless one gets a picture of its own.
                val defaultTrackArt: Map<String, String> = when {
                    category == Category.YOTO -> yotoChapters.mapNotNull { ch -> ch.iconUrl?.let { ch.key to it } }.toMap()
                    category == Category.AUDIOBOOK && artworkUrl.isNotBlank() -> tracks.associate { it.first to artworkUrl }
                    else -> emptyMap()
                }
                val editable = category == Category.PLAYLIST
                if (tracks.size > 1 || (editable && tracks.isNotEmpty())) {
                    Text(stringResource(if (editable) R.string.editor_tracks_songs_title else R.string.editor_tracks_each_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.editor_tracks_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Drag-to-reorder state (playlists only): which row is held, how far it has moved, row heights.
                    var dragIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffset by remember { mutableFloatStateOf(0f) }
                    val rowHeights = remember { mutableStateMapOf<Int, Int>() }
                    fun moveSong(from: Int, to: Int) {
                        if (from == to || from !in playlist.indices || to !in playlist.indices) return
                        val l = playlist.toMutableList()
                        val item = l.removeAt(from)
                        l.add(to, item)
                        playlist = l
                    }

                    tracks.take(80).forEachIndexed { index, (trackUri, trackTitle) ->
                      key(trackUri) {
                        val held = editable && dragIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { rowHeights[index] = it.size.height }
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (held) dragOffset else 0f
                                    scaleX = if (held) 1.02f else 1f
                                    scaleY = if (held) 1.02f else 1f
                                }
                                .then(if (held) Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)) else Modifier),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (editable) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.editor_drag_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .padding(8.dp)
                                        .pointerInput(playlist.size) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    dragIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    dragIndex = null
                                                    dragOffset = 0f
                                                }
                                            ) { change, amount ->
                                                change.consume()
                                                val from = dragIndex ?: return@detectDragGestures
                                                dragOffset += amount.y
                                                val h = (rowHeights[from] ?: 150).toFloat()
                                                if (dragOffset > h * 0.55f && from < playlist.lastIndex) {
                                                    moveSong(from, from + 1)
                                                    dragIndex = from + 1
                                                    dragOffset -= h
                                                } else if (dragOffset < -h * 0.55f && from > 0) {
                                                    moveSong(from, from - 1)
                                                    dragIndex = from - 1
                                                    dragOffset += h
                                                }
                                            }
                                        }
                                )
                            }
                            CardArtwork(
                                artwork = trackArt[trackUri] ?: defaultTrackArt[trackUri],
                                seed = uid + trackUri,
                                color = color,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { pickerTarget = trackUri },
                                cornerRadius = 10.dp,
                                pixel = appSettings.pixelArt
                            )
                            Spacer(Modifier.width(10.dp))
                            // Songs of a playlist can each be trimmed (Plus): a file / stream of the hand-built list,
                            // or a downloaded song of a YouTube playlist.
                            val trimSource: SongSource? = when {
                                category == Category.PLAYLIST -> SongSource.File(trackUri)
                                ytListTracks != null -> ytListTracks.firstOrNull { it.id == trackUri }
                                    ?.let { SongSource.File(android.net.Uri.fromFile(it.file).toString()) }
                                else -> null
                            }
                            val trackClip = clips[trackUri]?.takeIf { trimSource != null && it.isSet }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${index + 1}. $trackTitle",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (trackClip != null) {
                                    Text(
                                        clipLabel(trackClip),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (trimSource != null) {
                                IconButton(onClick = { openSongEditor(trackUri, trimSource) }) {
                                    Text(
                                        "✂",
                                        color = if (trackClip != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.editor_trim) }
                                    )
                                }
                            }
                            if (editable) {
                                IconButton(onClick = {
                                    playlist = playlist.filterNot { it.uri == trackUri }
                                    trackArt = trackArt - trackUri
                                }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.editor_remove_song_cd)) }
                            } else if (trackArt.containsKey(trackUri)) {
                                IconButton(onClick = { trackArt = trackArt - trackUri }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_remove_picture_cd))
                                }
                            }
                            if (category == Category.AUDIOBOOK) {
                                IconButton(onClick = {
                                    buildCardOrComplain()?.let { card ->
                                        viewModel.saveCard(card)
                                        val chapter = chapters.firstOrNull { it.artKey == trackUri }
                                        if (chapter != null) viewModel.playFrom(card, null, chapter.startMs) else viewModel.playFrom(card, trackUri, 0L)
                                    }
                                }) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.editor_book_play_from_cd)) }
                            }
                            if (!editable) {
                                OutlinedButton(onClick = { pickerTarget = trackUri }) { Text(stringResource(R.string.editor_pick)) }
                            }
                        }
                      }
                    }
                    if (editable) {
                        Text(
                            stringResource(R.string.editor_drag_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (tracks.size > 80) {
                        Text(stringResource(R.string.editor_first_80), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.editor_icon_title), style = MaterialTheme.typography.titleMedium)
                // Any emoji from the keyboard, or one of the suggestions in a single scrolling row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(8) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 24.sp),
                        modifier = Modifier.width(84.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        EmojiChoices.forEach { e ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (e == emoji) Color(color) else Color.Transparent)
                                    .clickable { emoji = e },
                                contentAlignment = Alignment.Center
                            ) { Text(e, fontSize = 24.sp) }
                        }
                    }
                }
                Text(stringResource(R.string.editor_colour), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    CardColorChoices.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == color) 4.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
            }
            looksSection()

            val tagAndShareSection: @Composable ColumnScope.() -> Unit = {
                if (saved != null) {
                    SectionTitle(stringResource(R.string.editor_section_tag))
                    Text(
                        stringResource(R.string.editor_tag_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                buildCardOrComplain()?.let {
                                    viewModel.saveCard(it)
                                    viewModel.startMove(it, copy = false)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.editor_move_tag)) }
                        OutlinedButton(
                            onClick = {
                                buildCardOrComplain()?.let {
                                    viewModel.saveCard(it)
                                    viewModel.startMove(it, copy = true)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.editor_copy_tag)) }
                    }
                }

                SectionTitle(stringResource(R.string.editor_section_share))
                val localOnly = category == Category.FILE || category == Category.FOLDER || category == Category.PLAYLIST || category == Category.AUDIOBOOK
                val shareable = !localOnly && uri.isNotBlank()
                Text(
                    stringResource(if (localOnly) R.string.editor_share_local_only else R.string.editor_share_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    enabled = shareable,
                    onClick = {
                        buildCardOrComplain()?.let {
                            viewModel.saveCard(it)
                            viewModel.startWrite(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.editor_write_to_card)) }
                Text(
                    stringResource(R.string.editor_boop_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (boopBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = !boopBusy,
                        onClick = sendBoop,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.editor_send_boop)) }
                    OutlinedButton(
                        enabled = !boopBusy,
                        onClick = {
                            buildCardOrComplain()?.let { card ->
                                viewModel.exportBoop(card) { file ->
                                    boopToSave = file
                                    boopSaver.launch(file.name)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.editor_save_boop)) }
                }
            }
            tagAndShareSection()

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showYotoPicker) {
        AlertDialog(
            onDismissRequest = { showYotoPicker = false },
            title = { Text(stringResource(R.string.editor_yoto_library_title)) },
            text = {
                val list = yotoCards
                when {
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.editor_loading))
                    }
                    list.isEmpty() -> Text(stringResource(R.string.editor_yoto_library_empty))
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        list.forEach { lc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        uri = lc.cardId
                                        name = lc.title
                                        artworkUrl = lc.coverUrl ?: ""
                                        subtitle = lc.category ?: lc.author
                                        trackArt = emptyMap()
                                        status = null
                                        showYotoPicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CardArtwork(
                                    artwork = lc.coverUrl,
                                    seed = lc.cardId,
                                    color = color,
                                    modifier = Modifier.size(52.dp),
                                    cornerRadius = 10.dp,
                                    pixel = false
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(lc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(lc.category, lc.author).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { yotoCards = null; showYotoPicker = true }) { Text(stringResource(R.string.editor_reload)) } },
            dismissButton = { TextButton(onClick = { showYotoPicker = false }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    songEditor?.let { (key, source) ->
        val main = key == Card.MAIN_TRACK
        SplitEditorDialog(
            source = source,
            initialChapters = if (main) chapters else emptyList(),
            initialClip = if (main) mainClip else clips[key],
            // One song of a playlist is trimmed only; the card's own file / video can be split too.
            allowSplit = main,
            onDone = { result, clip ->
                if (main) {
                    chapters = result
                    scannedChapters = true
                    mainClipUri = uri.trim()
                }
                clips = if (clip != null) clips + (key to clip) else clips - key
                songEditor = null
            },
            onDismiss = { songEditor = null }
        )
    }

    pickerTarget?.let { target ->
        val trackTitle = if (target.isEmpty()) null else when (category) {
            Category.FOLDER -> folderTracks.firstOrNull { it.uri == target }?.title
            Category.AUDIOBOOK -> folderTracks.firstOrNull { it.uri == target }?.title ?: chapters.firstOrNull { it.artKey == target }?.title
            Category.PODCAST -> feed?.episodes?.firstOrNull { it.url == target }?.title
            Category.PLAYLIST -> playlist.firstOrNull { it.uri == target }?.title
            Category.LINK, Category.FILE -> chapters.firstOrNull { it.artKey == target }?.title
            Category.YOTO -> yotoChapters.firstOrNull { it.key == target }?.title?.let { yotoPrettyTitle(it) }
            else -> null
        }
        ArtworkPickerDialog(
            initialQuery = (trackTitle ?: name).take(40),
            color = color,
            pixel = appSettings.pixelArt,
            onPick = { url, credit ->
                if (target.isEmpty()) {
                    artworkUrl = url
                    artworkCredit = credit
                } else {
                    trackArt = trackArt + (target to url)
                }
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null }
        )
    }

    when (val ws = writeState) {
        WriteState.Idle -> Unit
        is WriteState.Waiting -> AlertDialog(
            onDismissRequest = { viewModel.endWrite() },
            title = { Text(stringResource(R.string.editor_write_hold)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.editor_writing, ws.card.displayName(context)))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.endWrite() }) { Text(stringResource(R.string.common_cancel)) } }
        )
        is WriteState.Done -> AlertDialog(
            onDismissRequest = { viewModel.endWrite() },
            title = { Text(stringResource(R.string.editor_written)) },
            text = { Text(stringResource(R.string.editor_written_body, ws.card.displayName(context))) },
            confirmButton = { TextButton(onClick = { viewModel.endWrite() }) { Text(stringResource(R.string.common_done)) } }
        )
        is WriteState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.endWrite() },
            title = { Text(stringResource(R.string.editor_write_failed)) },
            text = { Text(ws.message) },
            confirmButton = { TextButton(onClick = { viewModel.startWrite(ws.card) }) { Text(stringResource(R.string.editor_try_again)) } },
            dismissButton = { TextButton(onClick = { viewModel.endWrite() }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    when (val ms = moveState) {
        MoveState.Idle -> Unit
        is MoveState.Waiting -> AlertDialog(
            onDismissRequest = { viewModel.endMove() },
            title = { Text(stringResource(if (ms.copy) R.string.editor_copy_hold else R.string.editor_move_hold)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (ms.copy) R.string.editor_copy_waiting else R.string.editor_move_waiting, ms.card.displayName(context))
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.endMove() }) { Text(stringResource(R.string.common_cancel)) } }
        )
        is MoveState.Done -> {
            val finish = {
                viewModel.endMove()
                if (!ms.copy) viewModel.back()
            }
            AlertDialog(
                onDismissRequest = finish,
                title = { Text(stringResource(if (ms.copy) R.string.editor_copied else R.string.editor_moved)) },
                text = {
                    Text(
                        stringResource(if (ms.copy) R.string.editor_copy_done else R.string.editor_move_done, ms.card.displayName(context))
                    )
                },
                confirmButton = { TextButton(onClick = finish) { Text(stringResource(R.string.common_done)) } }
            )
        }
        is MoveState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.endMove() },
            title = { Text(stringResource(if (ms.copy) R.string.editor_copy_failed else R.string.editor_move_failed)) },
            text = { Text(ms.message) },
            confirmButton = { TextButton(onClick = { viewModel.startMove(ms.card, ms.copy) }) { Text(stringResource(R.string.editor_try_again)) } },
            dismissButton = { TextButton(onClick = { viewModel.endMove() }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.deleteCard(uid)
                    viewModel.back()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "Plays 0:12 → 3:41" for a start / end cut. */
@Composable
private fun clipLabel(clip: Clip): String = stringResource(
    R.string.editor_trim_done,
    YouTubeChapters.formatTime(clip.startMs),
    if (clip.endMs > 0L) YouTubeChapters.formatTime(clip.endMs) else stringResource(R.string.editor_trim_end)
)

/** The card's start / end cut, with a button that plays it all again. Draws nothing without a cut. */
@Composable
private fun MainClipLine(clip: Clip?, onRemove: () -> Unit) {
    if (clip == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clipLabel(clip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text(stringResource(R.string.editor_trim_remove)) }
    }
}
