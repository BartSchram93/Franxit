package com.bartschram.franxit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** ⋮ → Language: the phone's language, English or Dutch. The choice applies at once. */
@Composable
fun LanguageDialog(choice: L10n.Choice, onChoose: (L10n.Choice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Language") },
        text = {
            Column {
                L10n.Choice.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(option) }.padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = option == choice, onClick = { onChoose(option) })
                        Spacer(Modifier.width(4.dp))
                        // Each language in its own name, so it can be found whatever the app says now.
                        Text(
                            when (option) {
                                L10n.Choice.SYSTEM -> "Phone's language"
                                L10n.Choice.ENGLISH -> "English"
                                L10n.Choice.DUTCH -> "Nederlands"
                                L10n.Choice.FRENCH -> "Français"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
