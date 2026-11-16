package com.aurawave.player.data

import android.content.Context
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Match
import com.aurawave.innertube.Track
import com.aurawave.player.data.db.HiddenRow
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/** A recommended song and the plain-language reason it was picked. */
@Serializable
data class Pick(val track: Track, val reason: String)

@Serializable
data class Mix(val id: String, val name: String, val subtitle: String, val picks: List<Pick>)

@Serializable
data class Feed(
    val hero: Mix? = null,
    val mixes: List<Mix> = emptyList(),
    val becauseSeed: Track? = null,
    val because: List<Pick> = emptyList(),
    val freshFinds: List<Pick> = emptyList(),
    val builtAt: Long = 0,
)

/** Shelves made only from the listener's own history: cheap, so they are rebuilt whenever Home opens. */
data class LocalShelves(val onRepeat: List<Pick>, val rediscover: List<Pick>)

/**
 * Builds the Home feed from the listener's own listening. Ported from the old app's
 * recommender.js:
 *
 *  1. Every song the listener touched gets an affinity weight: likes, full listens and
 *     plays push it up, skips pull it down, and old activity slowly fades.
 *  2. The strongest songs (at most two per artist, so one artist can't take over)
 *     become seeds. Each seed contributes its YouTube Music radio as candidates.
 *  3. Candidates are scored by how many of the listener's seeds point at them, how early
 *     they appear in those radios, and how much the listener likes their artists. Hidden
 *     songs and artists, and artists the listener keeps skipping, are kept out.
 *  4. The scored pool is cut into shelves, each song carrying a reason
 *     ("Because you played Tum Hi Ho"), so recommendations explain themselves.
 */
class Recommender(
    context: Context,
    private val library: Library,
    private val catalog: Catalog,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "feed.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow<Feed?>(null)
    val feed: StateFlow<Feed?> = state.asStateFlow()

    private val busy = MutableStateFlow(false)
    val building: StateFlow<Boolean> = busy.asStateFlow()

    private val failed = MutableStateFlow(false)
    /** True when the last build could not reach YouTube Music. */
    val offline: StateFlow<Boolean> = failed.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { json.decodeFromString(Feed.serializer(), file.readText()) }.getOrNull()
            if (saved != null && state.value == null) state.value = saved
        }
    }

    /** Rebuilds the feed when it is older than three hours, or always when [force] ("New picks"). */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!force) {
                // Wait for the saved feed to be read before deciding it is missing.
                val current = state.value ?: withContext(Dispatchers.IO) {
                    runCatching { json.decodeFromString(Feed.serializer(), file.readText()) }.getOrNull()
                }?.also { state.value = it }
                if (current != null && System.currentTimeMillis() - current.builtAt < FRESH_MS) return@launch
            }
            busy.value = true
            try {
                val built = build(force)
                state.value = built
                failed.value = false
                withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(Feed.serializer(), built)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed.value = true
            } finally {
                busy.value = false
            }
        }
    }

    /** Forget the feed, e.g. after the history was cleared or a backup restored. */
    fun invalidate() {
        job?.cancel()
        state.value = null
        scope.launch(Dispatchers.IO) { file.delete() }
    }

    // ---------- affinity ----------

    private class Entry(val track: Track) {
        var weight = 0.0
        var plays = 0
        var completes = 0
        var skips = 0
        var lastPlayed = 0L
        var liked = false
    }

    private class ArtistAcc(val name: String) {
        var weight = 0.0
        var skips = 0
        var plays = 0
    }

    private class Affinity(val tracks: Map<String, Entry>, val artists: Map<String, ArtistAcc>)

    private suspend fun affinity(): Affinity {
        val now = System.currentTimeMillis()
        val tracks = LinkedHashMap<String, Entry>()
        for (s in library.dao.songStats()) {
            if (s.song.kind == Kind.EPISODE.name) continue // podcasts are not music taste
            val e = tracks.getOrPut(s.song.videoId) { Entry(s.song.toTrack()) }
            val recency = exp(-(now - s.lastPlayed).toDouble() / (21 * DAY))
            e.plays = s.plays
            e.completes = s.completes
            e.skips = s.skips
            e.lastPlayed = s.lastPlayed
            e.weight += (s.plays * 0.4 + s.completes * 1.0 - s.skips * 1.3) * (0.35 + 0.65 * recency)
        }
        for (l in library.dao.likedSongs()) {
            if (l.song.kind == Kind.EPISODE.name) continue
            val e = tracks.getOrPut(l.song.videoId) { Entry(l.song.toTrack()) }
            e.liked = true
            e.weight += 3 * (0.5 + 0.5 * exp(-(now - l.likedAt).toDouble() / (60 * DAY)))
        }
        val artists = LinkedHashMap<String, ArtistAcc>()
        for (e in tracks.values) {
            val key = artistKey(e.track)
            if (key.isEmpty()) continue
            val a = artists.getOrPut(key) { ArtistAcc(leadArtist(e.track)) }
            a.weight += e.weight
            a.skips += e.skips
            a.plays += e.plays
        }
        for (name in settings.tasteArtists.value) {
            val key = Library.artistKey(name)
            if (key.isEmpty()) continue
            artists.getOrPut(key) { ArtistAcc(name) }.weight += 2
        }
        return Affinity(tracks, artists)
    }

    // ---------- seeds ----------

    private class Seed(val track: Track, val weight: Double, val fromTaste: String? = null) {
        val forArtist: String = fromTaste?.let(Library::artistKey) ?: artistKey(track)
    }

    private fun pickSeeds(aff: Affinity, count: Int, rand: Random): List<Seed> {
        val ranked = aff.tracks.values
            .filter { it.weight > 0.3 }
            .map { it to it.weight * (0.6 + rand.nextDouble() * 0.8) } // a little variety from day to day
            .sortedByDescending { it.second }
        val perArtist = HashMap<String, Int>()
        val seeds = ArrayList<Seed>()
        for ((e, _) in ranked) {
            val key = artistKey(e.track)
            if ((perArtist[key] ?: 0) >= 2) continue
            perArtist[key] = (perArtist[key] ?: 0) + 1
            seeds += Seed(e.track, e.weight)
            if (seeds.size >= count) break
        }
        return seeds
    }

    /** A new listener: a top song from each artist they picked, or popular artists in their language. */
    private suspend fun seedsFromTaste(): List<Seed> {
        var names = settings.tasteArtists.value.take(5)
        if (names.isEmpty()) {
            names = when (settings.tasteLanguages.value.firstOrNull()) {
                "Punjabi" -> listOf("Karan Aujla", "Diljit Dosanjh", "Shubh", "AP Dhillon")
                "English" -> listOf("The Weeknd", "Taylor Swift", "Billie Eilish", "Sabrina Carpenter")
                else -> listOf("Arijit Singh", "Pritam", "KK", "Sonu Nigam", "Shreya Ghoshal")
            }
        }
        return coroutineScope {
            names.map { name ->
                async {
                    val page = runCatching { catalog.artist("", name) }.getOrNull() ?: return@async null
                    val lead = page.songs.firstOrNull { Library.artistKey(it.artist) == Library.artistKey(page.name) } ?: page.songs.firstOrNull()
                    lead?.let { Seed(it, 2.0, fromTaste = page.name) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ---------- scoring ----------

    private class Candidate(val track: Track) {
        var score = 0.0
        val seeds = ArrayList<Seed>()
    }

    private fun score(radios: List<Pair<Seed, List<Track>>>, aff: Affinity, hidden: List<HiddenRow>, excludeKnown: Boolean): List<Candidate> {
        val pool = LinkedHashMap<String, Candidate>()
        val knownIds = aff.tracks.keys
        val knownKeys = aff.tracks.values.mapTo(HashSet()) { Match.songKey(it.track) }
        val seedKeys = radios.mapTo(HashSet()) { Match.songKey(it.first.track) }
        for ((seed, tracks) in radios) {
            tracks.forEachIndexed { pos, t ->
                val key = Match.songKey(t)
                if (key in seedKeys || library.isHidden(t, hidden)) return@forEachIndexed
                if (excludeKnown && (t.videoId in knownIds || key in knownKeys)) return@forEachIndexed
                val c = pool.getOrPut(key) { Candidate(t) }
                c.score += max(0.5, seed.weight) / (1 + pos / 12.0)
                if (seed !in c.seeds) c.seeds += seed
            }
        }
        for (c in pool.values) {
            aff.artists[artistKey(c.track)]?.let { a ->
                c.score *= 1 + ln(1 + max(0.0, a.weight)) * 0.25
                if (a.plays >= 3 && a.skips.toDouble() / a.plays > 0.6) c.score *= 0.3 // the listener keeps skipping this artist
            }
            c.score *= 1 + 0.35 * (c.seeds.size - 1) // several of the listener's seeds agree
        }
        return pool.values.sortedByDescending { it.score }
    }

    private fun reasonFor(c: Candidate, aff: Affinity): String {
        if (c.seeds.size >= 2) return "Linked to ${shortTitle(c.seeds[0].track.title)} and ${shortTitle(c.seeds[1].track.title)}"
        val seed = c.seeds.firstOrNull()
        if (seed?.fromTaste != null) return "For fans of ${seed.fromTaste}"
        val a = aff.artists[artistKey(c.track)]
        if (a != null && a.weight > 3) return "You often play ${a.name}"
        return seed?.let { "Because you played ${shortTitle(it.track.title)}" } ?: "Picked for you"
    }

    /** At most [maxPerArtist] songs per artist, keeping the order. */
    private fun diversify(list: List<Candidate>, maxPerArtist: Int, limit: Int): List<Candidate> {
        val counts = HashMap<String, Int>()
        val out = ArrayList<Candidate>()
        for (c in list) {
            val key = artistKey(c.track)
            if ((counts[key] ?: 0) >= maxPerArtist) continue
            counts[key] = (counts[key] ?: 0) + 1
            out += c
            if (out.size >= limit) break
        }
        return out
    }

    // ---------- the feed ----------

    private suspend fun build(force: Boolean): Feed = withContext(Dispatchers.Default) {
        val aff = affinity()
        val hidden = library.hidden.value
        val rand = Random(if (force) System.currentTimeMillis() else dayNumber())

        var seeds = pickSeeds(aff, 6, rand)
        if (seeds.size < 3) seeds = (seeds + seedsFromTaste()).take(6)
        if (seeds.isEmpty()) throw java.io.IOException("No seeds: YouTube Music could not be reached")

        val radios = coroutineScope {
            seeds.map { seed -> async { runCatching { seed to catalog.radio(seed.track.videoId) }.getOrNull() } }.awaitAll()
        }.filterNotNull().filter { it.second.isNotEmpty() }
        if (radios.isEmpty()) throw java.io.IOException("YouTube Music could not be reached")

        val fresh = score(radios, aff, hidden, excludeKnown = true)
        val everything = score(radios, aff, hidden, excludeKnown = false)

        // Hero mix: mostly discoveries, with one of the listener's favourites every few songs.
        val favourites = aff.tracks.values.filter { it.weight > 1 }.shuffled(rand).take(8)
        val hero = ArrayList<Pick>()
        var f = 0
        diversify(fresh, 2, 18).forEachIndexed { i, c ->
            hero += Pick(c.track, reasonFor(c, aff))
            if (i % 3 == 2 && f < favourites.size) hero += Pick(favourites[f++].track, "One of your favourites")
        }

        // Artist mixes: the listener's top artists, each blended with the artists their radios lead to.
        val usedCovers = HashSet<String>()
        val mixes = aff.artists.entries.sortedByDescending { it.value.weight }.take(4).mapNotNull { (key, a) ->
            val own = seeds.filter { it.forArtist == key }.map { Pick(it.track, "A favourite from ${a.name}") } +
                aff.tracks.values.filter { artistKey(it.track) == key }.sortedByDescending { it.weight }.take(5).map { Pick(it.track, "You play this a lot") }
            val related = everything.filter { c -> c.seeds.any { it.forArtist == key } || artistKey(c.track) == key }
            val relatedPicks = diversify(related, 2, 20).map { Pick(it.track, reasonFor(it, aff)) }
            val picks = ArrayList<Pick>()
            val seen = HashSet<String>()
            var oi = 0
            relatedPicks.forEachIndexed { i, p ->
                if (i % 3 == 0 && oi < own.size) {
                    val o = own[oi++]
                    if (seen.add(Match.songKey(o.track))) picks += o
                }
                if (seen.add(Match.songKey(p.track))) picks += p
            }
            if (picks.size < 6) return@mapNotNull null
            // Each mix opens with a cover no other mix uses, so they are easy to tell apart.
            val lead = picks.indexOfFirst { it.track.coverUrl !in usedCovers && artistKey(it.track) == key }
                .takeIf { it > 0 } ?: picks.indexOfFirst { it.track.coverUrl !in usedCovers }
            if (lead > 0) picks.add(0, picks.removeAt(lead))
            picks.take(3).forEach { usedCovers += it.track.coverUrl }
            val others = picks.map { leadArtist(it.track) }.distinct().filter { Library.artistKey(it) != key }.take(2)
            Mix(
                id = "mix-$key",
                name = "${a.name} Mix",
                subtitle = if (others.isNotEmpty()) "With ${others.joinToString(" and ")}" else "Your favourites and more",
                picks = picks,
            )
        }

        // "Because you played": the most recent song the listener finished or liked.
        val known = aff.tracks.values.mapTo(HashSet()) { Match.songKey(it.track) }
        val anchor = library.dao.latestPlays(50).firstNotNullOfOrNull { p -> aff.tracks[p.videoId]?.takeIf { it.weight > 0.8 }?.track }
            ?: seeds.first().track
        val anchorRadio = radios.firstOrNull { it.first.track.videoId == anchor.videoId }?.second
            ?: runCatching { catalog.radio(anchor.videoId) }.getOrDefault(emptyList())
        val because = anchorRadio.filter { Match.songKey(it) !in known && !library.isHidden(it, hidden) }.take(8)
            .map { Pick(it, "Often played after ${shortTitle(anchor.title)}") }

        Feed(
            hero = Mix("hero", "${timeOfDay()} Aura", "New finds and favourites, shaped by what you play", hero.take(25)).takeIf { hero.size >= 5 },
            mixes = mixes,
            becauseSeed = anchor.takeIf { because.isNotEmpty() },
            because = because,
            freshFinds = diversify(fresh.drop(18), 1, 14).map { Pick(it.track, reasonFor(it, aff)) },
            builtAt = System.currentTimeMillis(),
        )
    }

    /** On repeat and Rediscover, straight from the library. */
    suspend fun localShelves(): LocalShelves = withContext(Dispatchers.Default) {
        val aff = affinity()
        val now = System.currentTimeMillis()
        val all = aff.tracks.values
        LocalShelves(
            onRepeat = all.filter { it.plays >= 2 && now - it.lastPlayed < 30 * DAY.toLong() }
                .sortedWith(compareByDescending<Entry> { it.plays }.thenByDescending { it.lastPlayed })
                .take(10)
                .map { Pick(it.track, "${it.plays} plays this month") },
            rediscover = all.filter { (it.liked || it.completes >= 3) && it.lastPlayed > 0 && now - it.lastPlayed > 14 * DAY.toLong() }
                .sortedByDescending { it.weight }
                .take(12)
                .map { Pick(it.track, "Last played ${((now - it.lastPlayed) / DAY).toInt()} days ago") },
        )
    }

    companion object {
        private const val DAY = 86_400_000.0
        private const val FRESH_MS = 3 * 3_600_000L

        fun artistKey(track: Track): String = Library.artistKey(track.artists.firstOrNull()?.name ?: track.artist)

        fun leadArtist(track: Track): String =
            track.artists.firstOrNull()?.name ?: track.artist.split(',', '&').first().trim()

        private val CREDIT = Regex("""\s*\((from|feat\.?|ft\.?)[^)]*\)""", RegexOption.IGNORE_CASE)
        fun shortTitle(title: String): String = title.replace(CREDIT, "").trim().ifEmpty { title }

        private fun dayNumber(): Long {
            val c = Calendar.getInstance()
            return (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
        }

        fun timeOfDay(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..4 -> "Late-night"
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }
}
