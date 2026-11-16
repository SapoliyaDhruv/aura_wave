package com.aurawave.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin matcher must give the same answers as the original JavaScript one, kept as
 * src/test/fixtures/match.js. Expected values come from it (see gen-match-fixtures.mjs there).
 */
class MatchParityTest {
    private val fixtures = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/match-fixtures.json")) { "match-fixtures.json is missing" }
            .bufferedReader().readText(),
    ).jsonObject

    private fun cases(name: String): JsonArray = fixtures.getValue(name).jsonArray
    private fun JsonElement.s(i: Int) = jsonArray[i].jsonPrimitive.content
    private fun JsonElement.strings(i: Int) = jsonArray[i].jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun coreTitle() = cases("coreTitle").forEach { assertEquals(it.s(1), Match.coreTitle(it.s(0)), it.s(0)) }

    @Test
    fun fold() = cases("fold").forEach { assertEquals(it.s(1), Match.fold(it.s(0)), it.s(0)) }

    @Test
    fun versionFlags() {
        cases("versionFlags").forEach { assertEquals(it.strings(1).toSet(), Match.versionFlags(it.s(0)), it.s(0)) }
        cases("albumFlags").forEach { assertEquals(it.strings(1).toSet(), Match.versionFlags(it.s(0), album = true), it.s(0)) }
    }

    @Test
    fun splitArtists() = cases("splitArtists").forEach { assertEquals(it.strings(1), Match.splitArtists(it.s(0)), it.s(0)) }

    @Test
    fun titleSimilarity() = cases("titleSimilarity").forEach {
        val expected = it.jsonArray[2].jsonPrimitive.double
        val actual = Match.titleSimilarity(it.s(0), it.s(1))
        assertTrue(abs(expected - actual) < 1e-9, "${it.s(0)} vs ${it.s(1)}: expected $expected, got $actual")
    }

    @Test
    fun artistOverlap() = cases("artistOverlap").forEach {
        val e = it.jsonArray[2].jsonObject
        val a = Match.artistOverlap(it.s(0), it.s(1))
        val label = "${it.s(0)} vs ${it.s(1)}"
        assertEquals(e.getValue("primary").jsonPrimitive.boolean, a.primary, label)
        assertEquals(e.getValue("any").jsonPrimitive.boolean, a.any, label)
        assertTrue(abs(e.getValue("ratio").jsonPrimitive.double - a.ratio) < 1e-9, label)
    }

    @Test
    fun songKey() = cases("songKey").forEach { assertEquals(it.s(2), Match.songKey(it.s(0), it.s(1)), it.s(0)) }

    @Test
    fun scoreCandidate() {
        val all = cases("scoreCandidate")
        var accepted = 0
        all.forEach {
            val w = it.jsonArray[0].jsonObject
            val c = it.jsonArray[1].jsonObject
            val e = it.jsonArray[2].jsonObject
            val want = Match.Want(w.getValue("title").jsonPrimitive.content, w.getValue("artist").jsonPrimitive.content, w.getValue("duration").jsonPrimitive.int)
            val cand = Match.Candidate(
                title = c.getValue("title").jsonPrimitive.content,
                artist = c.getValue("artist").jsonPrimitive.content,
                album = c.getValue("album").jsonPrimitive.content,
                duration = c.getValue("duration").jsonPrimitive.int,
                kind = Kind.valueOf(c.getValue("kind").jsonPrimitive.content.uppercase()),
                channel = c.getValue("channel").jsonPrimitive.content,
            )
            val actual = Match.scoreCandidate(want, cand)
            val label = "$want vs $cand"
            assertEquals(e.getValue("accept").jsonPrimitive.boolean, actual.accept, label)
            assertEquals(e.getValue("reason").jsonPrimitive.content, actual.reason, label)
            assertTrue(abs(e.getValue("score").jsonPrimitive.double - actual.score) < 1e-6, "$label: expected ${e.getValue("score")}, got ${actual.score}")
            if (actual.accept) accepted++
        }
        // The fixture must exercise both outcomes, or the comparison above proves little.
        assertTrue(accepted in 1 until all.size, "accepted $accepted of ${all.size}")
    }
}
