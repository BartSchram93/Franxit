package com.bartschram.franxit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.Suggestions
import java.util.Locale

/**
 * Sets, changes or removes [product]'s price alert: [onSave] gets the new amount, or null to
 * remove it. Used on the detail screen and by long-pressing a card on the Overview, where
 * [showName] says which product it's for.
 */
@Composable
fun PriceAlertDialog(product: Product, onDismiss: () -> Unit, showName: Boolean = false, onSave: (Double?) -> Unit) {
    val nl = Locale.forLanguageTag("nl-BE")
    var text by rememberSaveable(product.id) { mutableStateOf(product.targetPrice?.let { String.format(nl, "%.2f", it) } ?: "") }
    val value = text.trim().replace("€", "").replace(" ", "").replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alert") },
        text = {
            Column {
                if (showName) {
                    Text(product.displayName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                }
                Text("Notify me when the cheapest price drops to or below:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                // One-tap amounts from the past year's prices.
                val suggestions = remember(product) { Suggestions.forProduct(product) }
                if (suggestions.isNotEmpty()) {
                    // However many there are, the suggestions share the full width.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            FilterChip(
                                selected = value == s.price,
                                onClick = { text = String.format(nl, "%.2f", s.price) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                        Text(s.price.euroRounded(), style = MaterialTheme.typography.labelLarge)
                                        Text(s.label, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                    // What the picked suggestion means; nothing until one is picked.
                    suggestions.firstOrNull { it.price == value }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    prefix = { Text("€ ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { onDismiss(); onSave(value) }) { Text("Save") }
        },
        dismissButton = {
            if (product.targetPrice != null) {
                TextButton(onClick = { onDismiss(); onSave(null) }) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
