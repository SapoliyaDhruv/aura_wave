package com.aurawave.player.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Everything the app knows about a song it has liked or played. */
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
interface LibraryDao {
    @Upsert
    suspend fun upsertSong(song: SongRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(row: LikeRow)

    @Query("DELETE FROM likes WHERE videoId = :videoId")
    suspend fun unlike(videoId: String)

    @Query("SELECT videoId FROM likes")
    fun likedIds(): Flow<List<String>>

    @Query("SELECT s.* FROM songs s JOIN likes l ON l.videoId = s.videoId ORDER BY l.likedAt DESC")
    fun liked(): Flow<List<SongRow>>

    @Insert
    suspend fun insertPlay(row: PlayRow): Long

    @Query("UPDATE plays SET listenedMs = :listenedMs, completed = :completed WHERE id = :id")
    suspend fun updatePlay(id: Long, listenedMs: Long, completed: Boolean)

    /** Songs by when they were last played, newest first, each song once. */
    @Query(
        """
        SELECT s.* FROM songs s
        JOIN (SELECT videoId, MAX(startedAt) AS lastPlayed FROM plays GROUP BY videoId) p ON p.videoId = s.videoId
        ORDER BY p.lastPlayed DESC
        LIMIT :limit
        """,
    )
    fun recentlyPlayed(limit: Int): Flow<List<SongRow>>
}

@Database(entities = [SongRow::class, LikeRow::class, PlayRow::class], version = 1)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
