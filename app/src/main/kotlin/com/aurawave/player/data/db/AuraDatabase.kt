package com.aurawave.player.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Everything the app knows about a song it has liked, played or put in a playlist. */
@Entity(tableName = "songs")
data class SongRow(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistsJson: String,
    val album: String,
    val albumId: String,
    val durationSec: Int,
    val coverUrl: String,
    val kind: String,
)

@Entity(tableName = "likes")
data class LikeRow(
    @PrimaryKey val videoId: String,
    val likedAt: Long,
)

/**
 * One listen of one song: when it started, how long the listener actually heard,
 * and whether they stayed to the end. History, statistics and recommendations all
 * come from this table.
 */
@Entity(tableName = "plays", indices = [Index("videoId"), Index("startedAt")])
data class PlayRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

/** A playlist the listener made or imported. [source] is "you", "spotify", "youtube" or "backup". */
@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val source: String = "you",
    /** Where it was imported from (a Spotify or YouTube playlist id), so a re-import updates it. */
    val sourceId: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("videoId")],
    foreignKeys = [ForeignKey(entity = PlaylistRow::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)],
)
data class PlaylistSongRow(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val addedAt: Long,
)

/** A song or artist the listener asked not to be recommended. [key] is a song key or a folded artist name. */
@Entity(tableName = "hidden")
data class HiddenRow(
    @PrimaryKey val key: String,
    val kind: String,
    val label: String,
    val at: Long,
)

/** "Not the right song?": the recording the listener chose for a song, by song key. */
@Entity(tableName = "pins")
data class PinRow(
    @PrimaryKey val songKey: String,
    val videoId: String,
    val at: Long,
)

@Entity(tableName = "searches")
data class SearchRow(
    @PrimaryKey val key: String,
    val query: String,
    val at: Long,
)

/** Per-song settings: how far to shift the synced lyrics. */
@Entity(tableName = "song_prefs")
data class SongPrefRow(
    @PrimaryKey val videoId: String,
    val lyricsOffsetMs: Long = 0,
)

/** An album the listener saved to their library. */
@Entity(tableName = "saved_albums")
data class SavedAlbumRow(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val year: String,
    val coverUrl: String,
    val type: String,
    val savedAt: Long,
)

/** An artist the listener follows. */
@Entity(tableName = "followed_artists")
data class FollowedArtistRow(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String,
    val followedAt: Long,
)

/** A podcast show the listener follows. */
@Entity(tableName = "followed_podcasts")
data class FollowedPodcastRow(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val followedAt: Long,
)

/** Where the listener got to in an episode, and whether they finished it. */
@Entity(tableName = "episode_progress", indices = [Index("updatedAt")])
data class EpisodeProgressRow(
    @PrimaryKey val videoId: String,
    val podcastId: String,
    val positionMs: Long,
    val durationMs: Long,
    val played: Boolean,
    val updatedAt: Long,
)

data class EpisodeInProgress(
    @Embedded val song: SongRow,
    val positionMs: Long,
    val durationMs: Long,
)

/** Per-song play statistics, the recommender's main input. */
data class SongStats(
    @Embedded val song: SongRow,
    val plays: Int,
    val completes: Int,
    val skips: Int,
    val lastPlayed: Long,
)

data class LikedSong(
    @Embedded val song: SongRow,
    val likedAt: Long,
)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistRow,
    val songCount: Int,
    val coverUrl: String?,
    /** The covers of the first few songs, one per line, for a playlist's four-cover picture. */
    val covers: String?,
) {
    val coverList: List<String> get() = covers?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
}

/** Plays per song over a period, for statistics and recommendations. */
data class SongPlays(
    @Embedded val song: SongRow,
    val plays: Int,
    val listenedMs: Long,
    val lastPlayed: Long,
)

fun Track.toRow(): SongRow = SongRow(
    videoId = videoId,
    title = title,
    artist = artist,
    artistsJson = Json.encodeToString(artists),
    album = album,
    albumId = albumId,
    durationSec = durationSec,
    coverUrl = coverUrl,
    kind = kind.name,
)

fun SongRow.toTrack(): Track = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    artists = runCatching { Json.decodeFromString<List<ArtistRef>>(artistsJson) }.getOrDefault(emptyList()),
    album = album,
    albumId = albumId,
    durationSec = durationSec,
    coverUrl = coverUrl,
    kind = runCatching { Kind.valueOf(kind) }.getOrDefault(Kind.SONG),
)

@Dao
abstract class LibraryDao {
    // ---------- songs ----------

    @Upsert
    abstract suspend fun upsertSong(song: SongRow)

    @Upsert
    abstract suspend fun upsertSongs(songs: List<SongRow>)

    /** Keeps what is already stored (a song's details rarely improve on a second sighting) but adds missing songs. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongsIfMissing(songs: List<SongRow>)

    @Query("SELECT * FROM songs")
    abstract suspend fun allSongs(): List<SongRow>

    @Query("SELECT * FROM songs WHERE videoId = :videoId")
    abstract suspend fun song(videoId: String): SongRow?

    // ---------- likes ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun like(row: LikeRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun likeIfMissing(rows: List<LikeRow>): List<Long>

    @Query("DELETE FROM likes WHERE videoId = :videoId")
    abstract suspend fun unlike(videoId: String)

    @Query("SELECT videoId FROM likes")
    abstract fun likedIds(): Flow<List<String>>

    @Query("SELECT s.* FROM songs s JOIN likes l ON l.videoId = s.videoId ORDER BY l.likedAt DESC")
    abstract fun liked(): Flow<List<SongRow>>

    @Query("SELECT * FROM likes")
    abstract suspend fun allLikes(): List<LikeRow>

    @Query("SELECT s.*, l.likedAt AS likedAt FROM songs s JOIN likes l ON l.videoId = s.videoId")
    abstract suspend fun likedSongs(): List<LikedSong>

    /** A skip is a play left within 30 seconds that did not reach the end. */
    @Query(
        """
        SELECT s.*, p.plays AS plays, p.completes AS completes, p.skips AS skips, p.lastPlayed AS lastPlayed FROM songs s
        JOIN (
            SELECT videoId, COUNT(*) AS plays,
                SUM(CASE WHEN completed THEN 1 ELSE 0 END) AS completes,
                SUM(CASE WHEN NOT completed AND listenedMs < 30000 THEN 1 ELSE 0 END) AS skips,
                MAX(startedAt) AS lastPlayed
            FROM plays GROUP BY videoId
        ) p ON p.videoId = s.videoId
        """,
    )
    abstract suspend fun songStats(): List<SongStats>

    /** The most recent plays, newest first, for "Because you played". */
    @Query("SELECT * FROM plays ORDER BY startedAt DESC LIMIT :limit")
    abstract suspend fun latestPlays(limit: Int): List<PlayRow>

    // ---------- plays ----------

    @Insert
    abstract suspend fun insertPlay(row: PlayRow): Long

    @Insert
    abstract suspend fun insertPlays(rows: List<PlayRow>)

    @Query("UPDATE plays SET listenedMs = :listenedMs, completed = :completed WHERE id = :id")
    abstract suspend fun updatePlay(id: Long, listenedMs: Long, completed: Boolean)

    @Query("SELECT * FROM plays ORDER BY startedAt")
    abstract suspend fun allPlays(): List<PlayRow>

    @Query("SELECT COUNT(*) FROM plays")
    abstract fun playCount(): Flow<Int>

    @Query("DELETE FROM plays")
    abstract suspend fun clearPlays()

    /** Songs by when they were last played, newest first, each song once. */
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN (SELECT videoId, MAX(startedAt) AS lastPlayed FROM plays GROUP BY videoId) p ON p.videoId = s.videoId
        ORDER BY p.lastPlayed DESC
        LIMIT :limit
        """,
    )
    abstract fun recentlyPlayed(limit: Int): Flow<List<SongRow>>

    /** Songs heard since [since] (a play counts once 30 seconds were heard or the song finished), most played first. */
    @Query(
        """
        SELECT s.*, p.plays AS plays, p.listenedMs AS listenedMs, p.lastPlayed AS lastPlayed FROM songs s
        JOIN (
            SELECT videoId, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(startedAt) AS lastPlayed
            FROM plays WHERE startedAt >= :since AND (completed OR listenedMs >= 30000)
            GROUP BY videoId
        ) p ON p.videoId = s.videoId
        WHERE s.kind != 'EPISODE'
        ORDER BY p.plays DESC, p.listenedMs DESC
        LIMIT :limit
        """,
    )
    abstract fun topSongs(since: Long, limit: Int): Flow<List<SongPlays>>

    @Query(
        """
        SELECT s.*, p.plays AS plays, p.listenedMs AS listenedMs, p.lastPlayed AS lastPlayed FROM songs s
        JOIN (
            SELECT videoId, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(startedAt) AS lastPlayed
            FROM plays WHERE startedAt >= :since AND (completed OR listenedMs >= 30000)
            GROUP BY videoId
        ) p ON p.videoId = s.videoId
        WHERE s.kind != 'EPISODE'
        """,
    )
    abstract suspend fun songPlaysSince(since: Long): List<SongPlays>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM plays WHERE startedAt >= :since")
    abstract fun listenedSince(since: Long): Flow<Long>

    // ---------- playlists ----------

    @Query(
        """
        SELECT p.*,
            (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
            (SELECT s.coverUrl FROM playlist_songs ps JOIN songs s ON s.videoId = ps.videoId
                WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS coverUrl,
            (SELECT GROUP_CONCAT(c, char(10)) FROM (
                SELECT s.coverUrl AS c FROM playlist_songs ps JOIN songs s ON s.videoId = ps.videoId
                WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 8)) AS covers
        FROM playlists p ORDER BY p.updatedAt DESC
        """,
    )
    abstract fun playlists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun playlist(id: Long): Flow<PlaylistRow?>

    @Query("SELECT * FROM playlists")
    abstract suspend fun allPlaylists(): List<PlaylistRow>

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.videoId = s.videoId WHERE ps.playlistId = :id ORDER BY ps.position")
    abstract fun playlistSongs(id: Long): Flow<List<SongRow>>

    @Query("SELECT videoId FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    abstract suspend fun playlistSongIds(id: Long): List<String>

    @Query("SELECT playlistId FROM playlist_songs WHERE videoId = :videoId")
    abstract fun playlistsContaining(videoId: String): Flow<List<Long>>

    @Insert
    abstract suspend fun insertPlaylist(row: PlaylistRow): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :at WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String, at: Long)

    @Query("UPDATE playlists SET updatedAt = :at WHERE id = :id")
    abstract suspend fun touchPlaylist(id: Long, at: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    abstract suspend fun lastPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlaylistSongs(rows: List<PlaylistSongRow>): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND videoId = :videoId")
    abstract suspend fun removeFromPlaylist(id: Long, videoId: String)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :id AND videoId = :videoId")
    abstract suspend fun setPosition(id: Long, videoId: String, position: Int)

    /** Appends songs that are not in the playlist yet. Returns how many were added. */
    @Transaction
    open suspend fun appendToPlaylist(id: Long, videoIds: List<String>, at: Long): Int {
        var next = lastPosition(id) + 1
        val rows = videoIds.distinct().map { PlaylistSongRow(id, it, next++, at) }
        val added = insertPlaylistSongs(rows).count { it != -1L }
        if (added > 0) touchPlaylist(id, at)
        return added
    }

    @Transaction
    open suspend fun reorderPlaylist(id: Long, videoIds: List<String>, at: Long) {
        videoIds.forEachIndexed { i, v -> setPosition(id, v, i) }
        touchPlaylist(id, at)
    }

    // ---------- hidden, pins, searches, per-song settings ----------

    @Upsert
    abstract suspend fun hide(row: HiddenRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun hideIfMissing(rows: List<HiddenRow>)

    @Query("SELECT * FROM hidden")
    abstract fun hidden(): Flow<List<HiddenRow>>

    @Query("SELECT * FROM hidden")
    abstract suspend fun allHidden(): List<HiddenRow>

    @Query("DELETE FROM hidden")
    abstract suspend fun clearHidden()

    @Upsert
    abstract suspend fun pin(row: PinRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun pinIfMissing(rows: List<PinRow>)

    @Query("SELECT * FROM pins WHERE songKey = :key")
    abstract suspend fun pinFor(key: String): PinRow?

    @Query("SELECT * FROM pins")
    abstract suspend fun allPins(): List<PinRow>

    @Upsert
    abstract suspend fun saveSearch(row: SearchRow)

    @Query("SELECT query FROM searches ORDER BY at DESC LIMIT :limit")
    abstract fun recentSearches(limit: Int): Flow<List<String>>

    @Query("SELECT query FROM searches ORDER BY at DESC")
    abstract suspend fun allSearches(): List<String>

    @Query("DELETE FROM searches WHERE `key` = :key")
    abstract suspend fun deleteSearch(key: String)

    @Query("DELETE FROM searches")
    abstract suspend fun clearSearches()

    @Query("DELETE FROM searches WHERE `key` NOT IN (SELECT `key` FROM searches ORDER BY at DESC LIMIT :keep)")
    abstract suspend fun trimSearches(keep: Int)

    // ---------- saved albums, followed artists ----------

    @Upsert
    abstract suspend fun saveAlbum(row: SavedAlbumRow)

    @Query("DELETE FROM saved_albums WHERE id = :id")
    abstract suspend fun unsaveAlbum(id: String)

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    abstract fun savedAlbums(): Flow<List<SavedAlbumRow>>

    @Query("SELECT * FROM saved_albums")
    abstract suspend fun allSavedAlbums(): List<SavedAlbumRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun saveAlbumsIfMissing(rows: List<SavedAlbumRow>)

    @Upsert
    abstract suspend fun follow(row: FollowedArtistRow)

    @Query("DELETE FROM followed_artists WHERE id = :id")
    abstract suspend fun unfollow(id: String)

    @Query("SELECT * FROM followed_artists ORDER BY name COLLATE NOCASE")
    abstract fun followedArtists(): Flow<List<FollowedArtistRow>>

    @Query("SELECT * FROM followed_artists")
    abstract suspend fun allFollowedArtists(): List<FollowedArtistRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun followIfMissing(rows: List<FollowedArtistRow>)

    @Query("UPDATE OR IGNORE likes SET videoId = :new WHERE videoId = :old")
    abstract suspend fun moveLike(old: String, new: String)

    @Query("UPDATE OR IGNORE playlist_songs SET videoId = :new WHERE videoId = :old")
    abstract suspend fun movePlaylistSongs(old: String, new: String)

    @Query("DELETE FROM playlist_songs WHERE videoId = :old")
    abstract suspend fun removeSongFromAllPlaylists(old: String)

    /** Puts [new] wherever [old] is liked or in a playlist (keeping its place). History keeps what was really played. */
    @Transaction
    open suspend fun replaceSong(old: String, new: SongRow) {
        upsertSong(new)
        moveLike(old, new.videoId)
        unlike(old)
        movePlaylistSongs(old, new.videoId)
        removeSongFromAllPlaylists(old)
    }

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun playlistById(id: Long): PlaylistRow?

    @Query("UPDATE playlists SET source = :source, sourceId = :sourceId WHERE id = :id")
    abstract suspend fun linkPlaylist(id: Long, source: String, sourceId: String)

    @Query("SELECT * FROM playlists WHERE source = :source AND sourceId = :sourceId LIMIT 1")
    abstract suspend fun playlistFromSource(source: String, sourceId: String): PlaylistRow?

    // ---------- podcasts ----------

    @Upsert
    abstract suspend fun followPodcast(row: FollowedPodcastRow)

    @Query("DELETE FROM followed_podcasts WHERE id = :id")
    abstract suspend fun unfollowPodcast(id: String)

    @Query("SELECT * FROM followed_podcasts ORDER BY followedAt DESC")
    abstract fun followedPodcasts(): Flow<List<FollowedPodcastRow>>

    @Query("SELECT * FROM followed_podcasts")
    abstract suspend fun allFollowedPodcasts(): List<FollowedPodcastRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun followPodcastsIfMissing(rows: List<FollowedPodcastRow>)

    @Upsert
    abstract suspend fun saveProgress(row: EpisodeProgressRow)

    @Query("SELECT * FROM episode_progress WHERE videoId = :videoId")
    abstract suspend fun progress(videoId: String): EpisodeProgressRow?

    @Query("SELECT * FROM episode_progress")
    abstract fun allProgress(): Flow<List<EpisodeProgressRow>>

    @Query("SELECT * FROM episode_progress")
    abstract suspend fun allProgressNow(): List<EpisodeProgressRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun saveProgressIfMissing(rows: List<EpisodeProgressRow>)

    /** Episodes started and not finished, most recent first. */
    @Query(
        """
        SELECT s.*, e.positionMs AS positionMs, e.durationMs AS durationMs FROM songs s
        JOIN episode_progress e ON e.videoId = s.videoId
        WHERE NOT e.played AND e.positionMs > 0
        ORDER BY e.updatedAt DESC LIMIT :limit
        """,
    )
    abstract fun inProgress(limit: Int): Flow<List<EpisodeInProgress>>

    @Upsert
    abstract suspend fun saveSongPref(row: SongPrefRow)

    @Query("SELECT * FROM song_prefs WHERE videoId = :videoId")
    abstract fun songPref(videoId: String): Flow<SongPrefRow?>

    @Query("SELECT * FROM song_prefs")
    abstract suspend fun allSongPrefs(): List<SongPrefRow>
}

@Database(
    entities = [
        SongRow::class, LikeRow::class, PlayRow::class,
        PlaylistRow::class, PlaylistSongRow::class, HiddenRow::class, PinRow::class, SearchRow::class, SongPrefRow::class,
        SavedAlbumRow::class, FollowedArtistRow::class, FollowedPodcastRow::class, EpisodeProgressRow::class,
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
