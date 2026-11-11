package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.PersonalizationHealth
import dev.lexicon.domain.PersonalizationHealthStatus
import dev.lexicon.domain.PersonalizationRecoveryReceipt

/** An explicit local recovery action; the repository backs up and verifies before replacement. */
@Composable
internal fun ProfileRecoveryControls(
    health: PersonalizationHealth?,
    busy: Boolean,
    error: String?,
    receipt: PersonalizationRecoveryReceipt?,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corrupt = health?.status == PersonalizationHealthStatus.CORRUPT
    val confirmed = !corrupt && receipt?.backupExists == true
    if (!corrupt && !confirmed && error == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag("profile_recovery")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (corrupt) {
                Text("训练偏好暂时无法读取", style = MaterialTheme.typography.titleSmall)
                Text("恢复默认训练偏好，原档案保留在本机。", Modifier.testTag("profile_recovery_consequence"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRecover, enabled = !busy && !health?.rowDigest.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("profile_recovery_action")) {
                    Text(if (busy) "正在恢复…" else "恢复默认偏好", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            } else if (confirmed) {
                Text("已恢复默认偏好，原档案已保留在本机。", Modifier.testTag("profile_recovery_success"),
                    style = MaterialTheme.typography.bodySmall)
                if (receipt?.profile?.enabled == false) Text("训练设置记忆已关闭，可按需重新开启。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, Modifier.testTag("profile_recovery_error"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
        }
    }
}
