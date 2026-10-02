package app.zenten.vault.ui.onboarding.createmasterkey

import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.BackupSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreateMasterKeyCreatingScreenTest {
    @Test
    fun `each derivation stage resolves only earlier operations`() {
        val nostrLines = linesFor(MasterKeyCreationStage.DERIVING_NOSTR_KEY_PAIR)
        val syncLines = linesFor(MasterKeyCreationStage.DERIVING_SYNC_CODE)

        assertEquals(listOf("master", "nostr"), nostrLines.map { it.text })
        assertFalse(nostrLines.first().active)
        assertTrue(nostrLines.last().active)
        assertEquals(listOf("master", "nostr", "sync code"), syncLines.map { it.text })
        assertTrue(syncLines.last().active)
        assertTrue(syncLines.dropLast(1).none { it.active })
    }

    @Test
    fun `backup transcript follows configured source order and type numbering`() {
        val progress = progress(activeSourceIndex = 2, completedSources = 2)
        val lines = linesFor(MasterKeyCreationStage.SYNCING_BACKUPS, progress)

        assertEquals(
            listOf("master", "nostr", "sync code", "relay 1", "relay 2", "sync server 1"),
            lines.map { it.text },
        )
        assertTrue(lines.last().active)
        assertTrue(lines.dropLast(1).none { it.active })
    }

    @Test
    fun `complete stage resolves every backup line`() {
        val progress = progress(activeSourceIndex = 4, completedSources = 5)
        val lines = linesFor(MasterKeyCreationStage.COMPLETE, progress)

        assertEquals(
            listOf(
                "master",
                "nostr",
                "sync code",
                "relay 1",
                "relay 2",
                "sync server 1",
                "sync server 2",
                "sync server 3",
            ),
            lines.map { it.text },
        )
        assertTrue(lines.none { it.active })
    }

    private fun linesFor(
        stage: MasterKeyCreationStage,
        progress: AccountConsolidationEngine.MasterBackupProgress? = null,
    ) = masterKeyCreationTerminalLines(
        stage = stage,
        progress = progress,
        creatingText = "master",
        nostrText = "nostr",
        syncCodeText = "sync code",
        relayTexts = listOf("relay 1", "relay 2"),
        syncServerTexts = listOf("sync server 1", "sync server 2", "sync server 3"),
    )

    private fun progress(activeSourceIndex: Int, completedSources: Int) =
        AccountConsolidationEngine.MasterBackupProgress(
            sources = listOf(
                AccountConsolidationEngine.MasterBackupSource("appdata1", BackupSourceType.NOSTR_RELAY),
                AccountConsolidationEngine.MasterBackupSource("appdata2", BackupSourceType.NOSTR_RELAY),
                AccountConsolidationEngine.MasterBackupSource("litesync1", BackupSourceType.BRAVE_SYNC),
                AccountConsolidationEngine.MasterBackupSource("litesync2", BackupSourceType.BRAVE_SYNC),
                AccountConsolidationEngine.MasterBackupSource("brave sync", BackupSourceType.BRAVE_SYNC),
            ),
            activeSourceIndex = activeSourceIndex,
            completedSources = completedSources,
        )
}
