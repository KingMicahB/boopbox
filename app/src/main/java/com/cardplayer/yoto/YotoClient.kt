package com.cardplayer.yoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.cardplayer.data.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Yoto's public API (yoto.dev): sign in with the family's Yoto account, list the family
 * library, read a card's chapters with playable stream URLs.
 *
 * Login is OAuth2 + PKCE through the phone's browser. Yoto only allows loopback redirects
 * for native apps, so a tiny HTTP server on 127.0.0.1:8787 catches the authorization code.
 */
class YotoClient(private val context: Context) {
    companion object {
        /** Public client id registered at dashboard.yoto.dev (public clients have no secret). */
        const val CLIENT_ID = "tpc_5dXCfSdHwcWigsZLCRCxBV"
        const val AUDIENCE = "https://api.yotoplay.com"
        const val SCOPE = "family:library:view offline_access"
        const val PORT = 8787
        const val REDIRECT = "http://127.0.0.1:$PORT/callback"
        const val LOGIN = "https://login.yotoplay.com"

        /** The link on a physical Yoto card's NFC tag: `https://yoto.io/<cardId>?<token>`. */
        fun isCardLink(s: String): Boolean {
            val uri = try {
                Uri.parse(s.trim())
            } catch (_: Exception) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            return (uri.scheme == "https" || uri.scheme == "http") &&
                (host == "yoto.io" || host.endsWith(".yoto.io")) &&
                !uri.pathSegments.isNullOrEmpty()
        }

        fun cardIdFromLink(s: String): String? =
            if (isCardLink(s)) Uri.parse(s.trim()).pathSegments.lastOrNull()?.ifBlank { null } else null
    }

    @Serializable
    data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    data class LibraryCard(val cardId: String, val title: String, val category: String?, val coverUrl: String?, val author: String?)
    data class Track(val key: String, val title: String?, val format: String?, val durationSec: Int, val trackUrl: String)
    data class Chapter(val key: String, val title: String, val availableFrom: String?, val iconUrl: String?, val tracks: List<Track>)
    data class CardDetail(
        val cardId: String,
        val title: String,
        val category: String?,
        val coverUrl: String?,
        /** "none" = play one chapter then stop (Yoto Daily), "yes" = carry on. */
        val autoAdvance: String?,
        val chapters: List<Chapter>
    )

    sealed class LoginState {
        data object Idle : LoginState()
        data object Waiting : LoginState()
        data object Done : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val store = JsonStore(File(context.filesDir, "yoto.json"), Tokens.serializer().nullable, null)
    val tokens: StateFlow<Tokens?> = store.state
    val isConnected: Boolean get() = store.value != null

    /** Re-reads yoto.json after a backup restore replaced it. */
    fun reloadTokens() = store.reload()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val refreshLock = Mutex()
    private var serverThread: Thread? = null

    // ---------------------------------------------------------------- login

    /** Opens Yoto's login page in the browser and waits for it to call back. */
    fun startLogin() {
        val verifier = base64Url(SecureRandom().let { r -> ByteArray(48).also(r::nextBytes) })
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = base64Url(SecureRandom().let { r -> ByteArray(12).also(r::nextBytes) })
        _loginState.value = LoginState.Waiting
        startCallbackServer(verifier, state)
        val url = Uri.parse("$LOGIN/authorize").buildUpon()
            .appendQueryParameter("audience", AUDIENCE)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("state", state)
            .build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            _loginState.value = LoginState.Error("No browser available to sign in with.")
        }
    }

    fun cancelLogin() {
        serverThread?.interrupt()
        serverThread = null
        if (_loginState.value == LoginState.Waiting) _loginState.value = LoginState.Idle
    }

    fun logout() {
        store.update { null }
        _loginState.value = LoginState.Idle
    }

    private fun startCallbackServer(verifier: String, expectedState: String) {
        serverThread?.interrupt()
        serverThread = thread(isDaemon = true, name = "yoto-login") {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
                    server.soTimeout = 5 * 60_000
                    while (!Thread.currentThread().isInterrupted) {
                        val socket = server.accept()
                        socket.use { s ->
                            val requestLine = s.getInputStream().bufferedReader().readLine() ?: ""
                            val path = requestLine.split(" ").getOrNull(1) ?: "/"
                            val uri = Uri.parse("http://127.0.0.1$path")
                            val code = uri.getQueryParameter("code")
                            val state = uri.getQueryParameter("state")
                            val ok = code != null && state == expectedState
                            val html = if (ok) doneHtml() else "<html><body><h3>BoopBox: waiting for Yoto…</h3></body></html>"
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            s.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(bytes)
                                flush()
                            }
                            if (ok) {
                                exchangeCode(code!!, verifier)
                                return@thread
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (_loginState.value == LoginState.Waiting) {
                    _loginState.value = LoginState.Error("Sign-in didn't complete: ${e.message ?: "timed out"}")
                }
            }
        }
    }

    private fun doneHtml(): String = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="1;url=intent://yoto-done#Intent;scheme=boopbox;package=${context.packageName};end">
        <style>body{font-family:sans-serif;text-align:center;padding:40px;background:#FFF6E9;color:#2B2118}
        a{display:inline-block;margin-top:24px;padding:16px 28px;background:#FF7A00;color:#fff;border-radius:16px;text-decoration:none;font-size:20px}</style></head>
        <body><h2>Yoto connected ✓</h2><p>You can go back to BoopBox.</p>
        <a href="intent://yoto-done#Intent;scheme=boopbox;package=${context.packageName};end">Back to BoopBox</a></body></html>
    """.trimIndent()

    private fun exchangeCode(code: String, verifier: String) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code_verifier", verifier)
                .add("code", code)
                .add("redirect_uri", REDIRECT)
                .build()
            val json = postToken(body)
            saveTokens(json)
            _loginState.value = LoginState.Done
        } catch (e: Exception) {
            _loginState.value = LoginState.Error("Couldn't finish signing in: ${e.message ?: "unknown error"}")
        }
    }

    private fun postToken(body: FormBody): JSONObject {
        val request = Request.Builder().url("$LOGIN/oauth/token").post(body).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("Yoto login HTTP ${r.code}: ${text.take(200)}")
            val json = JSONObject(text)
            if (json.has("error")) throw IllegalStateException(json.optString("error_description", json.optString("error")))
            return json
        }
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { store.value?.refreshToken ?: "" }
        val expiresIn = json.optLong("expires_in", 3600)
        store.update { Tokens(access, refresh, System.currentTimeMillis() + expiresIn * 1000) }
    }

    /** A valid access token, refreshing it when it is about to expire. Null when not signed in. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val current = store.value ?: return@withContext null
        if (current.expiresAt - System.currentTimeMillis() > 60_000) return@withContext current.accessToken
        refreshLock.withLock {
            val again = store.value ?: return@withLock null
            if (again.expiresAt - System.currentTimeMillis() > 60_000) return@withLock again.accessToken
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", again.refreshToken)
                    .build()
                saveTokens(postToken(body))
                store.value?.accessToken
            } catch (e: Exception) {
                // Refresh tokens are single-use; if it is gone the user has to sign in again.
                if (e.message?.contains("invalid_grant") == true) store.update { null }
                null
            }
        }
    }

    // ---------------------------------------------------------------- API

    private suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext null
        val request = Request.Builder()
            .url(AUDIENCE + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string() ?: return@withContext null
            if (!r.isSuccessful) throw IllegalStateException("Yoto HTTP ${r.code}: ${JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(120)}")
            JSONObject(text)
        }
    }

    /** Every card in the family library (owned cards and library-added shows like Yoto Daily). */
    suspend fun library(): List<LibraryCard> {
        val json = get("/card/family/library") ?: return emptyList()
        val arr = json.optJSONArray("cards") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val entry = arr.getJSONObject(i)
            val card = entry.optJSONObject("card") ?: entry
            val cardId = entry.optString("cardId").ifBlank { card.optString("cardId") }
            if (cardId.isBlank()) return@mapNotNull null
            val md = card.optJSONObject("metadata")
            LibraryCard(
                cardId = cardId,
                title = card.optString("title").ifBlank { "Yoto card" },
                category = md?.optString("category")?.ifBlank { null },
                coverUrl = md?.optJSONObject("cover")?.optString("imageL")?.ifBlank { null }?.substringBefore("?width="),
                author = md?.optString("author")?.ifBlank { null }
            )
        }.sortedBy { it.title.lowercase() }
    }

    /**
     * Chapters and fresh (signed, ~1 h) stream URLs for one card. [cardIdOrLink] is a library
     * card id (needs sign-in) or the yoto.io link read off a physical Yoto card (no sign-in).
     */
    suspend fun card(cardIdOrLink: String): CardDetail? {
        if (!isCardLink(cardIdOrLink)) {
            val json = get("/card/$cardIdOrLink") ?: return null
            return parseCard(cardIdOrLink, json.optJSONObject("card") ?: json)
        }
        return try {
            cardFromLink(cardIdOrLink)
        } catch (e: Exception) {
            // The page route failed; a signed-in family that owns the card can still get it by id.
            val id = cardIdFromLink(cardIdOrLink)
            if (id == null || !isConnected) throw e
            get("/card/$id")?.let { json -> parseCard(id, json.optJSONObject("card") ?: json) } ?: throw e
        }
    }

    /**
     * A physical card's NFC tag holds `https://yoto.io/<cardId>?<token>`. The token is the card's own
     * access key: that page embeds the card (chapters, signed track URLs) as Next.js page data.
     */
    private suspend fun cardFromLink(link: String): CardDetail = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("Accept", "text/html")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) BoopBox")
            .build()
        val html = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Yoto card page HTTP ${r.code}")
            r.body?.string() ?: ""
        }
        val data = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Yoto didn't recognise this card")
        val card = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("card")
            ?: throw IllegalStateException("Yoto didn't recognise this card")
        parseCard(card.optString("cardId").ifBlank { cardIdFromLink(link) ?: link }, card)
    }

    private fun parseCard(cardId: String, card: JSONObject): CardDetail {
        val content = card.optJSONObject("content")
        val md = card.optJSONObject("metadata")
        val chaptersJson = content?.optJSONArray("chapters")
        val chapters = (0 until (chaptersJson?.length() ?: 0)).mapNotNull { i ->
            val ch = chaptersJson!!.getJSONObject(i)
            val tracksJson = ch.optJSONArray("tracks")
            val tracks = (0 until (tracksJson?.length() ?: 0)).mapNotNull { j ->
                val t = tracksJson!!.getJSONObject(j)
                val url = t.optString("trackUrl").ifBlank { return@mapNotNull null }
                Track(
                    key = t.optString("key").ifBlank { "$i-$j" },
                    title = t.optString("title").ifBlank { null },
                    format = t.optString("format").ifBlank { null },
                    durationSec = t.optInt("duration", 0),
                    trackUrl = url
                )
            }
            if (tracks.isEmpty()) return@mapNotNull null
            Chapter(
                key = ch.optString("key").ifBlank { "ch$i" },
                title = ch.optString("title").ifBlank { "Chapter ${i + 1}" },
                availableFrom = ch.optString("availableFrom").ifBlank { null },
                iconUrl = ch.optJSONObject("display")?.optString("icon16x16")?.ifBlank { null },
                tracks = tracks
            )
        }
        return CardDetail(
            cardId = cardId,
            title = card.optString("title").ifBlank { "Yoto card" },
            category = md?.optString("category")?.ifBlank { null },
            coverUrl = md?.optJSONObject("cover")?.optString("imageL")?.ifBlank { null },
            autoAdvance = content?.optJSONObject("config")?.optString("autoadvance")?.ifBlank { null },
            chapters = chapters
        )
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

/** "25102026 Collage-ALong Bird 1" -> "Collage-ALong Bird" */
fun yotoPrettyTitle(raw: String): String =
    raw.replace(Regex("""^\d{8}\s+"""), "").replace(Regex("""\s+\d+$"""), "").trim().ifBlank { raw }
