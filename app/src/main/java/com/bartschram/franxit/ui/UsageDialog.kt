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
import androidx.compose.ui.unit.dp

private const val EXPLANATION =
    "Franxit counts anonymously how often it's used, so the maker knows if people use it. " +
        "It sends a random number made on your phone, the app version and how many times you open the app. " +
        "No name, no account, and never your products or prices."

/** ⋮ → Usage statistics: what is counted, and the switch to turn it off. */
@Composable
fun UsageDialog(enabled: Boolean, onChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage statistics") },
        text = {
            Column {
                Text(EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share anonymous counts", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Said once, on first start: what is counted, with the way to turn it off right there. */
@Composable
fun UsageNotice(onOk: () -> Unit, onTurnOff: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text("Anonymous counts") },
        text = {
            Text(
                "$EXPLANATION You can turn this off in ⋮ → Usage statistics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
        dismissButton = { TextButton(onClick = onTurnOff) { Text("Turn off") } },
    )
}
