package io.github.ioannes78.voica.ui.diarization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.database.DiarizationAttentionItem
import io.github.ioannes78.voica.database.DiarizationAttentionKind
import io.github.ioannes78.voica.productLabel
import io.github.ioannes78.voica.productMessage

@Composable
fun DiarizationAttentionBanner(
    attention: DiarizationAttentionItem,
    actionEnabled: Boolean,
    onRetry: () -> Unit,
    onIgnore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(attention.productLabel(), style = MaterialTheme.typography.titleMedium)
            Text(
                attention.productMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = actionEnabled, onClick = onRetry) {
                    Text(
                        if (attention.kind == DiarizationAttentionKind.ALIGNMENT) {
                            "重新应用"
                        } else {
                            "重新识别"
                        },
                    )
                }
                OutlinedButton(enabled = actionEnabled, onClick = onIgnore) {
                    Text("忽略")
                }
            }
        }
    }
}
