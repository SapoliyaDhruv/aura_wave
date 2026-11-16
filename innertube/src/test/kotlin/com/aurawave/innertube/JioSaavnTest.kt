package com.aurawave.innertube

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JioSaavnTest {
    private val saavn = JioSaavn(OkHttpClient())

    /** One real listing's scrambled address, and what it unscrambles to. */
    private val scrambled = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyRofh8YQPTqjR2tIxTYXqGEQHgqnNfsetouo4rsD0s4RZKObWAyHrEhw7tS9a8Gtq"
    private val listed = "https://aac.saavncdn.com/430/5c5ea5cc00e3bff45616013226f376fe_96.mp4"

    @Test
    fun `a listed stream address is unscrambled`() {
        assertEquals(listed, saavn.decode(scrambled))
        assertNull(saavn.decode("not an address"))
    }

    @Test
    fun `the address is chosen for the listener's quality`() {
        assertEquals(listed.replace("_96.", "_320.") to 320, saavn.streamUrl(listed, AudioQuality.HIGH, has320 = true))
        assertEquals(listed.replace("_96.", "_160.") to 160, saavn.streamUrl(listed, AudioQuality.HIGH, has320 = false))
        assertEquals(listed.replace("_96.", "_160.") to 160, saavn.streamUrl(listed, AudioQuality.NORMAL, has320 = true))
        assertEquals(listed to 96, saavn.streamUrl(listed, AudioQuality.LOW, has320 = true))
    }

    private val results = Json.parseToJsonElement(
        """
        {"results": [
          {"type": "song", "title": "Tum Hi Ho (Remix)", "more_info": {"album": "Aashiqui 2", "duration": "250",
            "encrypted_media_url": "x", "320kbps": "true", "artistMap": {"primary_artists": [{"name": "Arijit Singh"}]}}},
          {"type": "song", "title": "Tum Hi Ho", "more_info": {"album": "Aashiqui 2", "duration": "262",
            "encrypted_media_url": "$scrambled", "320kbps": "true",
            "artistMap": {"primary_artists": [{"name": "Mithoon"}, {"name": "Arijit Singh"}]}}},
          {"type": "album", "title": "Aashiqui 2"},
          {"type": "song", "title": "Sun Raha Hai Na Tu", "more_info": {"album": "Aashiqui 2", "duration": "390",
            "encrypted_media_url": "y", "320kbps": "false", "artistMap": {"primary_artists": [{"name": "Ankit Tiwari"}]}}}
        ]}
        """,
    )

    @Test
    fun `listings are read, and only songs`() {
        val listings = saavn.parse(results)
        assertEquals(listOf("Tum Hi Ho (Remix)", "Tum Hi Ho", "Sun Raha Hai Na Tu"), listings.map { it.title })
        assertEquals("Mithoon, Arijit Singh", listings[1].artists)
        assertEquals(262, listings[1].durationSec)
    }

    @Test
    fun `the same recording is picked, never another version or another song`() {
        val listings = saavn.parse(results)
        val found = saavn.pickRecording(listings, "Tum Hi Ho", "Arijit Singh, Mithoon", 262)
        assertNotNull(found)
        assertEquals("Tum Hi Ho", found.title)
        assertEquals(262, found.durationSec)
        assertNull(saavn.pickRecording(listings, "Kesariya", "Arijit Singh", 268))
    }
}
