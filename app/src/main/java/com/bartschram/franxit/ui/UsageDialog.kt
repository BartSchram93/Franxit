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

private const val EXPLANATION =
    "Franxit can count anonymously how often it's used, so the maker knows if people use it. " +
        "It sends a random number made on your phone, the app version, how many times you open the app " +
        "and how often you tap Buy at Bol.com. No name, no account, and never your products or prices."

/** ⋮ → Usage statistics: what is counted, the switch, and deleting your number. */
@Composable
fun UsageDialog(consent: Boolean, onChange: (Boolean) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(consent) }
    var deleted by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
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
                    Switch(checked = on, onCheckedChange = { on = it; deleted = false; onChange(it) })
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onDelete(); deleted = true }) {
                    Text(if (deleted) "Your number was deleted" else "Delete my number and data")
                }
                TextButton(onClick = { uri.openUri(UpdateCheck.PRIVACY_PAGE) }) { Text("Privacy policy") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Asked once, on first start: yes or no, equally easy. Franxit works the same either way, and
 * nothing is sent before a yes.
 */
@Composable
fun UsageConsent(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("Help improve Franxit?") },
        text = {
            Text(
                "$EXPLANATION\n\nIt's your choice: Franxit works the same either way. " +
                    "You can change it any time in ⋮ → Usage statistics, and delete your number there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onYes) { Text("Yes, count me in") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No thanks") } },
    )
}
