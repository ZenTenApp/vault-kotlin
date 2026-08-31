package app.zenten.vault.ui.accounts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalAccountBackupShareTest {
    @Test
    fun prioritizes_installed_supported_note_apps_only_once() {
        val targets = LocalAccountBackupShare.preferredTargets(
            listOf(
                target("com.google.android.keep", "KeepActivity"),
                target("com.google.android.keep", "AnotherKeepActivity"),
                target("example.messaging", "MessageActivity"),
                target("md.obsidian", "ObsidianActivity"),
            ),
        )

        assertEquals(listOf("com.google.android.keep", "md.obsidian"), targets.map { it.packageName })
        assertEquals(listOf("KeepActivity", "ObsidianActivity"), targets.map { it.activityName })
    }

    private fun target(packageName: String, activityName: String) =
        LocalAccountBackupShare.PreferredTarget(packageName, activityName)
}
