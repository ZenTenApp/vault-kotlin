package app.zenten.vault.ui.onboarding.createmasterkey

import android.widget.ImageView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.zenten.vault.R
import app.zenten.vault.ui.accounts.LocalAccountBackupShare

/** Direct target picker shared in behavior with associated-account local backup export. */
@Composable
internal fun BackupExportTargetDialog(
    targets: List<LocalAccountBackupShare.ShareTarget>,
    onDismiss: () -> Unit,
    onTargetSelected: (LocalAccountBackupShare.ShareTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_master_key_backup_target_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.create_master_key_backup_no_targets))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(targets, key = { "${it.packageName}/${it.activityName}" }) { target ->
                        TextButton(onClick = { onTargetSelected(target) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AndroidView(
                                    factory = { ImageView(it) },
                                    update = { imageView ->
                                        imageView.setImageDrawable(
                                            target.resolveInfo.loadIcon(imageView.context.packageManager),
                                        )
                                    },
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(target.label)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.create_master_key_backup_target_cancel))
            }
        },
    )
}
