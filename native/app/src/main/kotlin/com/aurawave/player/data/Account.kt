package com.aurawave.player.data

import android.content.Context
import android.webkit.CookieManager
import com.aurawave.innertube.AccountCookies
import com.aurawave.innertube.AccountInfo
import com.aurawave.innertube.InnerTube
import com.aurawave.player.data.db.FollowedArtistRow
import com.aurawave.player.data.db.LikeRow
import com.aurawave.player.data.db.SavedAlbumRow
import com.aurawave.player.data.db.toRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The optional YouTube Music sign-in. The login (the website's cookies) is kept in this
 * app's private storage on the phone and is sent only to YouTube, never to any server of
 * ours. Signed in, the app keeps liked songs, playlists, saved albums and followed artists
 * in step with YouTube Music, and can add plays to YouTube Music's history.
 */
class Account(
    private val context: Context,
    private val tube: InnerTube,
    private val library: Library,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    sealed interface Sync {
        /** [note]: something the listener should know about the last sync, or empty. */
        data class Idle(val lastSyncedAt: Long, val note: String = "") : Sync
        data class Working(val step: String) : Sync
        data class Failed(val message: String, val lastSyncedAt: Long) : Sync
    }

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val remoteLikesFile = File(context.filesDir, "account_remote_likes.txt")

    private val who = MutableStateFlow<AccountInfo?>(null)
    val info: StateFlow<AccountInfo?> = who.asStateFlow()

    private val syncing = MutableStateFlow<Sync>(Sync.Idle(prefs.getLong(LAST_SYNC, 0)))
    val sync: StateFlow<Sync> = syncing.asStateFlow()

    private var syncJob: Job? = null

    val signedIn: Boolean get() = tube.account != null

    init {
        val cookie = prefs.getString(COOKIE, null)
        if (cookie != null) {
            tube.account = AccountCookies(cookie)
            who.value = AccountInfo(prefs.getString(NAME, "").orEmpty(), prefs.getString(HANDLE, "").orEmpty(), prefs.getString(PHOTO, "").orEmpty())
            if (System.currentTimeMillis() - prefs.getLong(LAST_SYNC, 0) > AUTO_SYNC_MS) syncNow()
        }
        library.onLikeChanged = { track, liked -> mirror { if (liked) tube.like(track.videoId) else tube.removeLike(track.videoId) } }
        library.onSongsAdded = { playlistId, tracks ->
            mirror {
                val row = library.dao.playlistById(playlistId)
                if (row != null && row.source == SOURCE && row.sourceId.isNotEmpty()) tube.addToPlaylist(row.sourceId, tracks.map { it.videoId })
            }
        }
        library.onPlayStarted = { track ->
            if (settings.sendHistory.value) mirror { tube.addToHistory(track.videoId) }
        }
    }

    /** Called by the sign-in page with the cookies of music.youtube.com. Returns an error message, or null when signed in. */
    suspend fun signIn(cookie: String): String? {
        val login = AccountCookies(cookie)
        if (!login.isUsable) return "The sign-in did not finish. Try again."
        tube.account = login
        val me = try {
            tube.accountInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (me == null) {
            tube.account = null
            return "YouTube Music did not accept the sign-in. Try again."
        }
        prefs.edit()
            .putString(COOKIE, cookie)
            .putString(NAME, me.name)
            .putString(HANDLE, me.handle)
            .putString(PHOTO, me.photoUrl)
            .apply()
        who.value = me
        syncNow()
        return null
    }

    fun signOut() {
        syncJob?.cancel()
        tube.account = null
        who.value = null
        prefs.edit().clear().apply()
        remoteLikesFile.delete()
        syncing.value = Sync.Idle(0)
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    fun syncNow() {
        if (!signedIn || syncJob?.isActive == true) return
        syncJob = scope.launch {
            val last = prefs.getLong(LAST_SYNC, 0)
            syncing.value = try {
                val note = runSync()
                val now = System.currentTimeMillis()
                prefs.edit().putLong(LAST_SYNC, now).apply()
                Sync.Idle(now, note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Sync.Failed(
                    if ((e as? com.aurawave.innertube.InnerTubeException)?.status in setOf(401, 403)) {
                        "YouTube Music signed you out. Sign in again."
                    } else {
                        "Couldn't reach YouTube Music. Sync will try again later."
                    },
                    last,
                )
            } catch (e: Exception) {
                Sync.Failed("Sync stopped: ${e.message ?: e.javaClass.simpleName}", last)
            }
        }
    }

    /** Returns a note for the listener when something was held back, otherwise an empty text. */
    private suspend fun runSync(): String {
        val dao = library.dao
        var note = ""

        // Liked songs, both ways. The likes seen on YouTube last time tell apart "unliked
        // there" (remove here) from "liked here, not sent yet" (send there).
        syncing.value = Sync.Working("Liked songs")
        val remote = tube.likedSongs()
        val remoteIds = remote.mapTo(LinkedHashSet()) { it.videoId }
        val now = System.currentTimeMillis()
        // Read from the database, not the in-memory set, which is still empty just after launch.
        val local = dao.allLikes().mapTo(HashSet()) { it.videoId }
        val lastRemote = readRemoteLikes()
        val plan = planLikeSync(local, remoteIds, lastRemote)
        val likeHere = plan.likeHere.toSet()
        val newThere = remote.filter { it.videoId in likeHere }
        dao.insertSongsIfMissing(newThere.map { it.toRow() })
        dao.likeIfMissing(newThere.mapIndexed { i, t -> LikeRow(t.videoId, now - i * 1000L) })
        // A liked list that came back much shorter than last time is more likely a change on
        // YouTube's side (or a page that failed to load) than the listener unliking half their
        // library. Nothing is taken off the phone on that evidence.
        val heldBack = if (likesLookCutShort(remoteIds.size, lastRemote.size)) plan.unlikeHere else emptyList()
        if (heldBack.isEmpty()) {
            plan.unlikeHere.forEach { dao.unlike(it) }
        } else {
            note = "YouTube Music listed far fewer liked songs than last time, so no likes were removed from this phone."
        }
        // Only changes YouTube Music accepted are recorded as being there. One that failed is
        // tried again by the next sync, instead of looking like a song that was unliked there.
        val removedThere = plan.unlikeThere.filter { id -> push { tube.removeLike(id) } }
        val addedThere = plan.likeThere.filter { id -> push { tube.like(id) } }
        // Held-back songs stay on record as liked there, so the next sync looks at them again.
        writeRemoteLikes(remoteIds + addedThere + heldBack - removedThere.toSet())

        // Playlists: each YouTube Music playlist has a copy here that gains its new songs.
        // Songs added here to such a copy are sent there as they are added (see init), not
        // here: re-sending everything missing there would undo removals made on YouTube Music.
        syncing.value = Sync.Working("Playlists")
        for (p in tube.libraryPlaylists()) {
            val tracks = runCatching { tube.authPlaylistTracks(p.id) }.getOrNull() ?: continue
            val existing = library.importedPlaylist(SOURCE, p.id)
            val id = existing?.id ?: library.createPlaylist(p.title, source = SOURCE, sourceId = p.id)
            dao.insertSongsIfMissing(tracks.map { it.toRow() })
            dao.appendToPlaylist(id, tracks.map { it.videoId }, now)
        }

        syncing.value = Sync.Working("Albums and artists")
        runCatching { tube.savedAlbums() }.getOrNull()?.let { albums ->
            dao.saveAlbumsIfMissing(albums.map { SavedAlbumRow(it.id, it.title, it.artist, it.year, it.coverUrl, it.type, now) })
        }
        runCatching { tube.subscribedArtists() }.getOrNull()?.let { artists ->
            dao.followIfMissing(artists.map { FollowedArtistRow(it.id, it.name, it.imageUrl, now) })
        }
        return note
    }

    /** Puts a playlist made here into YouTube Music and keeps the two linked. */
    suspend fun copyToYouTubeMusic(playlistId: Long): String? {
        if (!signedIn) return "Sign in to YouTube Music first, in Settings."
        val row = library.dao.playlistById(playlistId) ?: return "That playlist no longer exists."
        val ids = library.dao.playlistSongIds(playlistId)
        val remoteId = try {
            tube.createPlaylist(row.name, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return "YouTube Music did not create the playlist. Try again."
        library.dao.linkPlaylist(playlistId, SOURCE, remoteId)
        return null
    }

    /** Sends one change during a sync and says whether YouTube Music accepted it. */
    private suspend fun push(change: suspend () -> Unit): Boolean {
        val accepted = try {
            change()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        delay(PUSH_GAP_MS)
        return accepted
    }

    /** Sends a change to YouTube Music in the background; a failure is picked up by the next sync. */
    private fun mirror(block: suspend () -> Unit) {
        if (!signedIn) return
        scope.launch { runCatching { block() } }
    }

    private suspend fun readRemoteLikes(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { remoteLikesFile.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    }

    private suspend fun writeRemoteLikes(ids: Collection<String>) = withContext(Dispatchers.IO) {
        runCatching { remoteLikesFile.writeText(ids.joinToString("\n")) }
    }

    companion object {
        const val SOURCE = "ytm"
        private const val COOKIE = "cookie"
        private const val NAME = "name"
        private const val HANDLE = "handle"
        private const val PHOTO = "photo"
        private const val LAST_SYNC = "last_sync"
        private const val AUTO_SYNC_MS = 6 * 3_600_000L
        private const val PUSH_GAP_MS = 300L

        /** Where the sign-in page starts. */
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    }
}

/** What a sync of liked songs has to change, on the phone and on YouTube Music. */
internal data class LikePlan(
    /** Liked on YouTube Music since the last sync: like here too. */
    val likeHere: List<String>,
    /** Unliked on YouTube Music since the last sync: unlike here. */
    val unlikeHere: List<String>,
    /** Liked here and never seen on YouTube Music: like there. */
    val likeThere: List<String>,
    /** Unliked here while the change could not be sent: unlike there. */
    val unlikeThere: List<String>,
)

/**
 * Compares the likes on the phone ([local]), on YouTube Music now ([remote]) and on YouTube
 * Music at the last sync ([lastRemote], empty the first time). A song on one side only was
 * added there if the last sync did not see it, and removed from the other side if it did.
 */
internal fun planLikeSync(local: Set<String>, remote: Set<String>, lastRemote: Set<String>): LikePlan {
    val onlyThere = remote.filter { it !in local }
    val onlyHere = local.filter { it !in remote }
    return LikePlan(
        likeHere = onlyThere.filter { it !in lastRemote },
        unlikeHere = onlyHere.filter { it in lastRemote },
        likeThere = onlyHere.filter { it !in lastRemote },
        unlikeThere = onlyThere.filter { it in lastRemote },
    )
}

/**
 * True when the liked list from YouTube Music is under half the length it had at the last
 * sync, for a library big enough that this cannot be a few ordinary unlikes.
 */
internal fun likesLookCutShort(remoteNow: Int, remoteLastSync: Int): Boolean =
    remoteLastSync >= 20 && remoteNow < remoteLastSync / 2
