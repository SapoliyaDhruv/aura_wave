package com.aurawave.innertube

import kotlin.test.Test
import kotlin.test.assertEquals

class LrcParseTest {
    @Test
    fun readsTimesInOrder() {
        val lines = Lrclib.parseLrc("[00:12.50] second\n[00:01.2] first\n[01:02:345] third")
        assertEquals(listOf(LyricLine(1_200, "first"), LyricLine(12_500, "second"), LyricLine(62_345, "third")), lines)
    }

    @Test
    fun aLineWithSeveralStampsRepeats() {
        val lines = Lrclib.parseLrc("[00:10.00][00:40.00] chorus\n[00:20.00] verse")
        assertEquals(listOf(10_000L to "chorus", 20_000L to "verse", 40_000L to "chorus"), lines.map { it.timeMs to it.text })
    }

    @Test
    fun positiveOffsetShowsLyricsSooner() {
        val lines = Lrclib.parseLrc("[offset: 500]\n[00:10.00] a\n[00:00.20] b")
        assertEquals(listOf(0L to "b", 9_500L to "a"), lines.map { it.timeMs to it.text })
    }

    @Test
    fun emptyLinesCollapseToOnePause() {
        // Metadata tags are ignored; a gap keeps one empty marker, and a leading gap is dropped.
        val lrc = "[ar: Someone]\n[00:00.00]\n[00:05.00] one\n[00:10.00]\n[00:12.00]\n[00:15.00] two"
        val lines = Lrclib.parseLrc(lrc)
        assertEquals(listOf(5_000L to "one", 10_000L to "", 15_000L to "two"), lines.map { it.timeMs to it.text })
    }
}
