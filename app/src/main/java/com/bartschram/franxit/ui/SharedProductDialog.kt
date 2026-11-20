package com.bartschram.franxit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Images
import com.bartschram.franxit.data.Repository
import com.bartschram.franxit.data.ShareLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Track this product?" for a product someone shared: its photo, name and lowest price, read from its page
 * while the dialog is open, so you see what you would track. Track works right away, also while it loads;
 * when the page can't be read, it just says "A shared product".
 */
@Composable
fun SharedProductDialog(
    shared: ShareLink.Shared,
    preview: suspend (String) -> Repository.Preview?,
    onTrack: (name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // (still loading, what was read)
    val state by produceState<Pair<Boolean, Repository.Preview?>>(true to null, shared.url) {
        value = false to runCatching { preview(shared.url) }.getOrNull()
    }
    val (loading, found) = state
    val photo by produceState<ImageBitmap?>(null, found?.image) {
        val url = found?.image ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Images.thumbnail(url)?.asImageBitmap() }.getOrNull() }
    }
    val name = found?.name ?: shared.name.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track this product?") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading || found?.image != null) {
                    ProductPhoto(photo, 64.dp, 14.dp)
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    when {
                        name != null -> Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        loading -> LoadingDots()
                        else -> Text("A shared product")
                    }
                    found?.price?.let {
                        Text(t("from %s", it.euro()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onTrack(name) }) { Text("Track") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
