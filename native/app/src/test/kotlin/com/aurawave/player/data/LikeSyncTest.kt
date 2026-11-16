package com.aurawave.player.data

import kotlin.test.Test
import kotlin.test.assertEquals

class LikeSyncTest {
    @Test
    fun `the first sync brings both sides together and removes nothing`() {
        val plan = planLikeSync(local = setOf("a", "b"), remote = setOf("b", "c"), lastRemote = emptySet())
        assertEquals(listOf("c"), plan.likeHere)
        assertEquals(listOf("a"), plan.likeThere)
        assertEquals(emptyList(), plan.unlikeHere)
        assertEquals(emptyList(), plan.unlikeThere)
    }

    @Test
    fun `a song unliked on one side since the last sync is unliked on the other`() {
        val plan = planLikeSync(local = setOf("a", "b"), remote = setOf("a", "c"), lastRemote = setOf("a", "b", "c"))
        assertEquals(listOf("b"), plan.unlikeHere) // gone from YouTube Music
        assertEquals(listOf("c"), plan.unlikeThere) // gone from the phone
        assertEquals(emptyList(), plan.likeHere)
        assertEquals(emptyList(), plan.likeThere)
    }

    @Test
    fun `a like YouTube Music did not accept is sent again, not removed from the phone`() {
        val first = planLikeSync(local = setOf("a"), remote = emptySet(), lastRemote = emptySet())
        assertEquals(listOf("a"), first.likeThere)

        // The push failed, so "a" was not recorded as being on YouTube Music.
        val next = planLikeSync(local = setOf("a"), remote = emptySet(), lastRemote = emptySet())
        assertEquals(listOf("a"), next.likeThere)
        assertEquals(emptyList(), next.unlikeHere)

        // Had it been recorded anyway, the next sync would have taken the like off the phone.
        val wronglyRecorded = planLikeSync(local = setOf("a"), remote = emptySet(), lastRemote = setOf("a"))
        assertEquals(listOf("a"), wronglyRecorded.unlikeHere)
    }
}
