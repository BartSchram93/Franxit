package com.bartschram.franxit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small product photos, downloaded once per product and kept in the app's files. */
object Images {

    private const val SIZE = 192

    fun file(context: Context, productId: Long) = File(File(context.filesDir, "images").apply { mkdirs() }, "$productId.jpg")

    /** Downloads [url] as this product's photo, unless it was already saved from the same url. */
    fun save(context: Context, productId: Long, url: String) {
        val target = file(context, productId)
        val marker = File(target.path + ".src")
        if (target.exists() && marker.exists() && marker.readText() == url) return

        val conn = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Franxit")
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return
        val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / (it * 2) < SIZE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        marker.writeText(url)
    }

    fun delete(context: Context, productId: Long) {
        val target = file(context, productId)
        File(target.path + ".src").delete()
        target.delete()
    }

    fun load(context: Context, productId: Long): Bitmap? =
        file(context, productId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
