package com.bartschram.franxit.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bartschram.franxit.R
import com.bartschram.franxit.scrape.PriceTag
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.vision.text.Text as ReadText

/**
 * Search by a photo of a shop's price tag: take one with the camera app or choose one. The product's name
 * is read from it on the phone itself (no upload, the price is left out: see PriceTag), and "Is this the
 * product?" shows it, to search for as it is or to change first.
 */
@Composable
fun PriceTagSearch(onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    // After reading: the name to confirm or change ("" when none was found); null while there's nothing to ask.
    var found by remember { mutableStateOf<String?>(null) }
    val photo = remember { PriceTagPhotos.newUri(context) }

    fun read(uri: Uri) {
        reading = true
        scope.launch {
            val name = runCatching { PriceTagPhotos.name(context, uri) }.getOrNull()
            reading = false
            found = name.orEmpty()
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) read(photo) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::read) }

    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.ic_photo_camera), t("Photo of a price tag"))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                menu = false
                // Without a camera app there's nothing to open; choosing a photo still works.
                runCatching { camera.launch(photo) }
            })
            DropdownMenuItem(text = { Text("Choose a photo") }, onClick = {
                menu = false
                runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            })
        }
    }

    if (reading) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingDots()
                    Spacer(Modifier.width(14.dp))
                    Text("Reading the price tag…")
                }
            },
            confirmButton = {},
        )
    }

    found?.let { name ->
        var text by remember(name) { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Is this the product?") },
            text = {
                Column {
                    if (name.isEmpty()) {
                        Text("No name found on the photo. Type it yourself.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Product name") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { found = null; onSearch(text.trim()) }, enabled = text.trim().length >= 2) {
                    Text("Search")
                }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }
}

/** The photo of a price tag: where the camera app saves it, and the name read from it. */
private object PriceTagPhotos {

    /** The one place a new photo is saved (each one replaces the last): the app's cache, shared with the camera app. */
    fun newUri(context: Context): Uri {
        val file = File(File(context.cacheDir, "tags").apply { mkdirs() }, "tag.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    }

    /** The product's name on the photo at [uri] (see PriceTag); null when none was found. Read on the phone itself. */
    suspend fun name(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val read = suspendCancellableCoroutine<ReadText> { done ->
                reader.process(image)
                    .addOnSuccessListener { done.resume(it) }
                    .addOnFailureListener { done.resumeWithException(it) }
            }
            val lines = read.textBlocks.flatMap { it.lines }.map { line ->
                PriceTag.Line(line.text, line.boundingBox?.top ?: 0, line.boundingBox?.height() ?: 0)
            }
            PriceTag.name(lines)
        } finally {
            reader.close()
        }
    }
}
