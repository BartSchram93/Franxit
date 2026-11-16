package com.bartschram.franxit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Shops

/**
 * ⋮ → My discounts: your personal discounts per shop. Shops are picked from the ones seen in
 * your products and shown by their website (see [Shops]). A shop's discount also counts for its
 * partners (bol.com for "Bol Plaza"), so each shop is listed once.
 */
@Composable
fun DiscountsDialog(
    discounts: Map<String, Double>,
    shops: List<String>,
    onSet: (shop: String, percent: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    val percent = text.trim().replace("%", "").replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 && it < 100 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(adding?.let(Shops::name) ?: if (picking) "Which shop?" else "My discounts") },
        text = {
            when {
                adding != null -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Discount") },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                picking -> {
                    val choices = shops.filter { Discounts.percentFor(it, discounts) == null }
                    if (choices.isEmpty()) {
                        Text("No other shops yet. Shops appear here once a product lists them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(choices) { shop ->
                                Text(
                                    Shops.name(shop),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { adding = shop; picking = false; text = "" }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                else -> Column {
                    Text(
                        "Add your shop discounts (e.g. Benefits@Work) to see where you pay least.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (discounts.isEmpty()) {
                        Text("No discounts yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    discounts.entries.sortedBy { it.key.lowercase() }.forEachIndexed { i, (shop, pct) ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Shops.name(shop), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${Discounts.label(pct)}%", style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { onSet(shop, null) }) { Icon(Icons.Default.Delete, "Remove discount") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                adding != null -> TextButton(enabled = percent != null, onClick = {
                    onSet(adding!!, percent)
                    adding = null
                }) { Text("Save") }
                picking -> {}
                else -> TextButton(onClick = { picking = true }) { Text("Add discount") }
            }
        },
        dismissButton = {
            if (adding != null || picking) {
                TextButton(onClick = { adding = null; picking = false }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
