package com.bartschram.franxit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.bartschram.franxit.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private const val SIGN_UP = "https://serpapi.com/users/sign_up"

/** ⋮ → Google Shopping: turn it on with a free SerpApi key (kept on the phone only), or off again. */
@Composable
fun ShoppingDialog(
    hasKey: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google Shopping") },
        text = {
            Column {
                if (hasKey) {
                    Text(
                        "You'll see extra shops for your products, updated once a day.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Find more shops for your products. It's free:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Make a free account at SerpApi\n2. Copy your key\n3. Paste it below",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { uri.openUri(SIGN_UP) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Get a free key")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Your key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (hasKey) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text("Turn on") }
            }
        },
        dismissButton = {
            if (hasKey) {
                TextButton(onClick = { onSave(null) }) { Text("Turn off") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
