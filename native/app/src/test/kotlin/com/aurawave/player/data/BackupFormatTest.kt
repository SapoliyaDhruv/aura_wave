package com.aurawave.player.data

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupFormatTest {
    private fun kindOf(text: String) = backupKind(BackupJson.parseToJsonElement(text).jsonObject)

    @Test
    fun `a backup this app writes is recognised when it is restored`() {
        val written = BackupJson.encodeToString(BackupFile.serializer(), BackupFile(exportedAt = "2026-11-16T10:00:00Z"))
        assertEquals(BackupKind.NATIVE, kindOf(written))
    }

    @Test
    fun `backups from the old app are recognised`() {
        assertEquals(BackupKind.OLD_APP, kindOf("""{"app":"AuraWave","version":2,"liked":[]}"""))
        assertEquals(BackupKind.OLDEST_APP, kindOf("""{"favoriteMeta":[],"customPlaylists":[]}"""))
    }

    @Test
    fun `any other file is refused`() {
        assertNull(kindOf("""{"songs":[],"liked":[]}"""))
    }
}
