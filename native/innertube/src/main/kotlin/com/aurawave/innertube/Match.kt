package com.aurawave.innertube

import java.text.Normalizer

/**
 * Song identity and matching. Answers one question reliably: "is this recording the
 * song the listener asked for?", so a same-named song by another artist, an
 * instrumental, a karaoke track or a slowed/lofi edit never plays in place of the original.
 *
 * Ported from the web app's shared/match.js. MatchParityTest checks both give the same answers.
 */
object Match {
    // If a candidate carries one of these markers and the requested title does not,
    // it is a different recording of the song (or not the song at all).
    private val VERSION_MARKERS: List<Pair<String, Regex>> = listOf(
        "instrumental" to Regex("""\binstrumental\b|\bwithout vocals?\b|\bno vocals?\b|\bmusic only\b|\bminus one\b|\bbacking track\b|\bbgm\b|\bbackground (music|score)\b|\btheme music\b|\bdhun\b"""),
        "karaoke" to Regex("""\bkaraoke\b|\bsing ?along\b|\boriginally performed\b|\bin the style of\b"""),
        "lofi" to Regex("""\blo-?fi\b"""),
        "slowed" to Regex("""\bslowed\b|\breverb\b"""),
        "spedup" to Regex("""\bsped ?up\b|\bspeed ?up\b|\bnightcore\b"""),
        "remix" to Regex("""\bremix\b|\bre-?mix\b|\bmash-?up\b|\bdj\b|\bclub mix\b|\bdhol mix\b|\bbootleg\b|\bflip\b"""),
        "cover" to Regex("""\bcover\b|\btribute\b|\bfan made\b|\breplay\b|\brecreated\b"""),
        "live" to Regex("""\blive\b|\bconcert\b|\bin session\b|\bmtv unplugged\b"""),
        "unplugged" to Regex("""\bunplugged\b|\bacoustic\b|\bstripped\b"""),
        "reprise" to Regex("""\breprise\b|\breprised\b"""),
        "female" to Regex("""\bfemale( version)?\b"""),
        "male" to Regex("""\bmale version\b"""),
        "spatial" to Regex("""\b8d\b|\b16d\b|\b3d audio\b|\bbass ?boost(ed)?\b|\bjhankar\b"""),
        "solo" to Regex("""\b(piano|flute|guitar|violin|sitar|cello|saxophone|orchestral|music box) (version|cover)\b"""),
        "clip" to Regex("""\bringtone\b|\bstatus\b|\bshorts?\b|\breel\b|\bteaser\b|\btrailer\b|\bscene\b|\bdialogue\b|\breaction\b|\btutorial\b|\bhow to play\b"""),
    )

    /** Markers that say little when they appear in an album name ("Short n' Sweet", "Unplugged Sessions"). */
    private val WEAK_IN_ALBUM = setOf("clip", "cover", "unplugged", "solo")

    /** Altered versions that are hidden from search unless the listener asks for them. */
    val HIDDEN_VERSIONS = setOf("instrumental", "karaoke", "slowed", "spedup", "spatial", "clip")

    private val SEPARATORS = Regex("""[_|#]+""")
    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val BRACKET_CREDIT = Regex("""\((from|feat\.?|ft\.?|featuring|with|prod\.?|official|lyric|full|audio|video|from the)[^)]*\)""")
    private val SQUARE_BRACKETS = Regex("""\[[^\]]*]""")
    private val DASH_SUFFIX = Regex("""\s[-–—|]\s.*$""")
    private val FEAT_SUFFIX = Regex("""\b(feat\.?|ft\.?|featuring)\s.*$""")
    private val QUOTES = Regex("""[“”"'’`]""")
    private val NOT_ALNUM_RUN = Regex("""[^\p{L}\p{N}]+""")
    private val NOT_ALNUM = Regex("""[^\p{L}\p{N}]""")
    private val SPACES = Regex("""\s+""")
    private val REPEATED = Regex("""(.)\1+""")
    private val TRAILING_Y = Regex("y$")
    private val ASPIRATED = Regex("([bcdfgjklmnpqrstvx])h")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|•)\s*""")
    private val DASH_SPLIT = Regex("""\s[-–—|]\s""")

    fun versionFlags(text: String?, album: Boolean = false): Set<String> {
        val t = " " + text.orEmpty().lowercase().replace(SEPARATORS, " ") + " "
        val flags = LinkedHashSet<String>()
        for ((flag, re) in VERSION_MARKERS) {
            if (album && flag in WEAK_IN_ALBUM) continue
            if (re.containsMatchIn(t)) flags += flag
        }
        return flags
    }

    private fun stripDiacritics(s: String?): String =
        Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKD).replace(COMBINING, "")

    /** Removes bracketed credits ("(From "Film")", "(feat. X)", "[Official Audio]") from a title. */
    fun coreTitle(title: String?): String = stripDiacritics(title)
        .lowercase()
        .replace(BRACKET_CREDIT, " ")
        .replace(SQUARE_BRACKETS, " ")
        .replace(DASH_SUFFIX, " ") // "Song - From Film", "Song | Artist"
        .replace(FEAT_SUFFIX, " ")
        .replace(QUOTES, "")
        .replace("&", " and ")
        .replace(NOT_ALNUM_RUN, " ")
        .replace(SPACES, " ")
        .trim()

    /** Phonetic folding for romanised Hindi/Punjabi spellings (Raataan ≈ Raatan, Maahi ≈ Mahi). */
    fun fold(s: String?): String = stripDiacritics(s)
        .lowercase()
        .replace(NOT_ALNUM, "")
        .replace(REPEATED, "\$1")
        .replace("ee", "i")
        .replace("oo", "u")
        .replace("w", "v")
        .replace("z", "j")
        .replace(TRAILING_Y, "i")
        .replace(ASPIRATED, "\$1")

    private fun tokens(s: String?): List<String> = coreTitle(s).split(' ').filter { it.isNotEmpty() }

    private fun bigrams(s: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            out[g] = (out[g] ?: 0) + 1
        }
        return out
    }

    private fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val x = bigrams(a)
        val y = bigrams(b)
        var inter = 0
        for ((g, n) in x) inter += minOf(n, y[g] ?: 0)
        val total = maxOf(1, a.length - 1 + b.length - 1)
        return 2.0 * inter / total
    }

    /** 0..1 similarity between two song titles, tolerant of spelling and credit noise. */
    fun titleSimilarity(a: String?, b: String?): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val fa = fold(ta.joinToString(""))
        val fb = fold(tb.joinToString(""))
        if (fa == fb) return 1.0
        val setA = ta.map(::fold).toSet()
        val setB = tb.map(::fold).toSet()
        val inter = setA.count { it in setB }
        val tokenScore = 2.0 * inter / (setA.size + setB.size)
        // Character similarity is discounted when lengths differ a lot, so "Tere Bina"
        // does not match "Tere Bin Nahi Laage" just because one starts like the other.
        val lengthRatio = minOf(fa.length, fb.length).toDouble() / maxOf(fa.length, fb.length)
        return maxOf(tokenScore, dice(fa, fb) * (0.5 + 0.5 * lengthRatio))
    }

    /** Splits an artist credit like "Pritam, Arijit Singh & Shreya Ghoshal" into names. */
    fun splitArtists(credit: String?): List<String> = stripDiacritics(credit)
        .lowercase()
        .split(ARTIST_SPLIT)
        .map { it.replace(NOT_ALNUM, "") }
        .filter { it.length >= 2 && it != "various" && it != "variousartists" }

    private fun artistEq(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))) return true
        return fold(a) == fold(b)
    }

    data class Overlap(val primary: Boolean, val any: Boolean, val ratio: Double)

    /** How well a candidate's artists cover the requested credit. */
    fun artistOverlap(requested: String?, candidate: String?): Overlap {
        val req = splitArtists(requested)
        val cand = splitArtists(candidate)
        if (req.isEmpty() || cand.isEmpty()) return Overlap(primary = false, any = false, ratio = 0.0)
        val hits = req.count { r -> cand.any { c -> artistEq(r, c) } }
        return Overlap(
            primary = cand.any { c -> artistEq(req[0], c) },
            any = hits > 0,
            ratio = hits.toDouble() / req.size,
        )
    }

    /** True when the free text mentions any of the requested artists (for video titles/channels). */
    fun mentionsArtist(requested: String?, text: String?): Boolean {
        val hay = stripDiacritics(text).lowercase().replace(NOT_ALNUM, "")
        val foldedHay = fold(hay)
        return splitArtists(requested).any { a -> a.length >= 3 && (hay.contains(a) || foldedHay.contains(fold(a))) }
    }

    data class Want(val title: String, val artist: String = "", val duration: Int = 0)

    data class Candidate(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val duration: Int = 0,
        val kind: Kind = Kind.SONG,
        val channel: String = "",
    )

    data class Score(val score: Double, val accept: Boolean, val reason: String)

    /** Scores how likely [cand] is the requested recording. */
    fun scoreCandidate(want: Want, cand: Candidate): Score {
        val wantFlags = versionFlags(want.title)
        val candFlags = versionFlags(cand.title).toMutableSet()
        candFlags += versionFlags(cand.album, album = true)
        for (f in candFlags) {
            if (f !in wantFlags) return Score(-1000.0, false, "different version ($f)")
        }

        // Video titles often read "Artist - Song (Official Video)"; also score the part after the dash.
        val dash = if (cand.kind != Kind.SONG) cand.title.split(DASH_SPLIT) else emptyList()
        val titleVariants = listOf(cand.title) + dash.drop(1)
        val sim = titleVariants.maxOf { titleSimilarity(want.title, it) }
        val wantedCore = coreTitle(want.title)
        val hay = " " + stripDiacritics(cand.title).lowercase().replace(NOT_ALNUM_RUN, " ") + " "
        val containsWanted = wantedCore.length >= 4 && hay.contains(" $wantedCore ")
        if (sim < 0.55 && !containsWanted) return Score(-500.0, false, "title mismatch")

        // Clips: anything far shorter than a song (status videos, previews, shorts).
        if (cand.duration in 1..74 && !(want.duration in 1..89)) {
            return Score(-800.0, false, "too short to be the song")
        }

        // Video titles are keyword-stuffed, so "contains the title" is weaker evidence there.
        val containsCredit = if (containsWanted) (if (cand.kind == Kind.SONG) 0.8 else 0.65) else 0.0
        var score = maxOf(sim, containsCredit) * 100
        for (f in wantFlags) if (f !in candFlags) score -= 40

        val titleOnlyScore = score
        var artistOk = want.artist.isEmpty()
        if (want.artist.isNotEmpty()) {
            val primary: Boolean
            val any: Boolean
            if (cand.kind != Kind.SONG) {
                primary = mentionsArtist(want.artist, "${cand.title} ${cand.channel} ${cand.artist}")
                any = false
            } else {
                val o = artistOverlap(want.artist, cand.artist)
                primary = o.primary
                any = o.any
            }
            when {
                primary -> { score += 60; artistOk = true }
                any -> { score += 40; artistOk = true }
                else -> score -= 80
            }
        }

        var exactDuration = false
        if (want.duration > 0 && cand.duration > 0) {
            val d = kotlin.math.abs(want.duration - cand.duration)
            exactDuration = d <= 2
            when {
                d <= 3 -> score += 30
                d <= 8 -> score += 20
                d <= 15 -> score += 5
                d > 60 -> score -= 90
                d > 30 -> score -= 50
            }
        }

        // Official audio tracks are cleanest; music videos may carry intros; uploads are least trusted.
        if (cand.kind == Kind.VIDEO) score -= 15 else if (cand.kind == Kind.UPLOAD) score -= 35

        // Accept when title + artist agree, or (no artist known) the title agrees.
        // Credits sometimes name the composer instead of the singer, so an official audio track
        // with an exact title and a duration within 2s is accepted even without an artist match.
        val accept = (artistOk && score >= 120) ||
            (want.artist.isEmpty() && sim >= 0.85) ||
            (cand.kind == Kind.SONG && sim >= 0.97 && exactDuration && titleOnlyScore >= 95)

        return Score(score, accept, if (accept) "match" else "low confidence")
    }

    /** Same song regardless of which upload (used to avoid near-duplicates in recommendations). */
    fun songKey(title: String?, artist: String?): String =
        "${fold(coreTitle(title))}:${fold(splitArtists(artist).firstOrNull().orEmpty())}"

    fun songKey(track: Track): String = songKey(track.title, track.artist)
}
