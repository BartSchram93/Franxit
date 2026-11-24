package com.bartschram.franxit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.bartschram.franxit.scrape.ProductPhoto
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.vision.text.Text as ReadText

/**
 * Search by a photo of a shop's price tag: take one with the camera app or choose one. The product's name
 * is read from it on the phone itself (no upload, the price is left out: see PriceTag), and "Is this the
 * product?" shows it, to search for as it is or to change first.
 *
 * A photo without a clear price tag (of the product itself) can be recognised online instead, by Google Lens
 * (through the app's search service), but only when you choose so: the photo then leaves the phone. The price tag
 * always comes first.
 */
@Composable
fun PriceTagSearch(onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    // After reading: the name to confirm or change ("" when none was found); null while there's nothing to ask.
    var found by remember { mutableStateOf<String?>(null) }
    // Why the name is empty or may be off, shown above it (online recognition failed or saw nothing).
    var note by remember { mutableStateOf<String?>(null) }
    // A photo without a clear price tag: whether to recognise the product on it online. Its text so far, if any.
    var askOnline by remember { mutableStateOf<Pair<Uri, String?>?>(null) }
    val photo = remember { PriceTagPhotos.newUri(context) }

    fun read(uri: Uri) {
        reading = true
        note = null
        scope.launch {
            val reading0 = runCatching { PriceTagPhotos.read(context, uri) }.getOrNull()
            reading = false
            if (reading0?.clear == true) found = reading0.name.orEmpty() else askOnline = uri to reading0?.name
        }
    }

    fun recognize(uri: Uri, fallback: String?) {
        recognizing = true
        scope.launch {
            val result = runCatching { PriceTagPhotos.recognize(context, uri) }
            recognizing = false
            val name = result.getOrNull()
            note = when {
                result.isFailure -> result.exceptionOrNull()?.message?.takeIf { it == ProductPhoto.TOO_MANY }
                    ?: "The product couldn't be recognised. Type its name."
                name == null -> "Nothing recognised on the photo. Type its name."
                else -> null
            }
            found = name ?: fallback.orEmpty()
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

    if (reading || recognizing) {
        // A small card with just the dots and the words: an AlertDialog without buttons left an empty strip below.
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingDots()
                    Spacer(Modifier.width(14.dp))
                    Text(if (reading) "Reading the price tag…" else "Recognising the product…")
                }
            }
        }
    }

    askOnline?.let { (uri, text) ->
        AlertDialog(
            onDismissRequest = { askOnline = null },
            title = { Text("No price tag found") },
            text = {
                Text(
                    "Recognise the product on the photo online? The photo is then sent to Google Lens.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { askOnline = null; recognize(uri, text) }) { Text("Recognise") }
            },
            dismissButton = {
                TextButton(onClick = { askOnline = null; note = null; found = text.orEmpty() }) { Text("Type it myself") }
            },
        )
    }

    found?.let { name ->
        var text by remember(name) { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Is this the product?") },
            text = {
                Column {
                    val message = note ?: "No name found on the photo. Type it yourself.".takeIf { name.isEmpty() }
                    if (message != null) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
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

    /** The product's name on the photo at [uri] (see PriceTag), and whether it is clearly a price tag's. Read on the phone itself. */
    suspend fun read(context: Context, uri: Uri): PriceTag.Reading = withContext(Dispatchers.IO) {
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
            PriceTag.read(lines)
        } finally {
            reader.close()
        }
    }

    /** The largest side of a photo sent to be recognised: enough for Vision, small to send. */
    private const val MAX_SIDE = 1024

    /** The largest photo the recognition service takes (SerpApi's limit is 500 KB). */
    private const val MAX_BYTES = 480_000

    /** The product on the photo at [uri], recognised online (see ProductPhoto); null when nothing was. */
    suspend fun recognize(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        ProductPhoto.recognize(smallJpeg(context, uri) ?: throw java.io.IOException("Photo can't be read"))
    }

    /** The photo made small (at most [MAX_SIDE] pixels a side) as JPEG: only what Vision needs is sent. */
    private fun smallJpeg(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val side = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (side / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        // The recognition service takes at most 500 KB: a lower quality until it fits, then a smaller photo. A phone's
        // photo of 10 MB or more is a few hundred KB by then.
        var photo = small
        repeat(3) {
            for (quality in listOf(85, 70, 55)) {
                val jpeg = ByteArrayOutputStream().use { out -> photo.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
                if (jpeg.size <= MAX_BYTES) return jpeg
            }
            photo = Bitmap.createScaledBitmap(photo, photo.width * 3 / 4, photo.height * 3 / 4, true)
        }
        return null
    }
}
