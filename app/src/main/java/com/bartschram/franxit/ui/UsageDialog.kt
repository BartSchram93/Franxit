package com.bartschram.franxit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.UpdateCheck

/** ⋮ → Usage statistics: what is counted, and the switch to turn it off. */
@Composable
fun UsageDialog(enabled: Boolean, onChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(enabled) }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage statistics") },
        text = {
            Column {
                Text(
                    "Franxit counts anonymously how many phones use it each day, so the maker knows if people use it. " +
                        "Once a day it sends \"one more phone\" and the app version, nothing else: no number that " +
                        "identifies your phone, no name, no account, and never your products or prices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share the anonymous daily count", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { uri.openUri(UpdateCheck.PRIVACY_PAGE) }) { Text("Privacy policy") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
