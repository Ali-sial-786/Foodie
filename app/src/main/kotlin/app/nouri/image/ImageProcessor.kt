package app.nouri.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.nouri.core.ai.ImagePayload
import app.nouri.core.ai.ImageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** A processed photo ready for analysis, saved privately so the user can review it. */
data class ProcessedImage(val payload: ImagePayload, val file: File)

/**
 * Prepares photos for analysis: applies EXIF orientation and user rotation,
 * optional centre crop, downscales to [maxEdgePx] and re-encodes as JPEG. Re-encoding
 * drops all metadata (GPS, device info), so originals never leave the device.
 */
class ImageProcessor(
    private val context: Context,
    private val maxEdgePx: Int = 1600,
    private val jpegQuality: Int = 85,
) {
    private val directory: File get() = File(context.filesDir, "scans").apply { mkdirs() }

    suspend fun fromUri(uri: Uri, role: ImageRole, extraRotation: Int = 0, cropFraction: Float = 1f, name: String): ProcessedImage =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val orientation = resolver.openInputStream(uri)?.use(::exifRotation) ?: 0
            val bitmap = resolver.openInputStream(uri)?.use { bounds ->
                val data = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open the image")
                decodeSampled(bounds, data)
            } ?: throw IllegalArgumentException("Couldn't open the image")
            finish(bitmap, orientation + extraRotation, cropFraction, role, name)
        }

    suspend fun fromFile(file: File, role: ImageRole, extraRotation: Int = 0, cropFraction: Float = 1f, name: String): ProcessedImage =
        withContext(Dispatchers.IO) {
            val orientation = file.inputStream().use(::exifRotation)
            val bitmap = file.inputStream().use { decodeSampled(it, file.inputStream()) }
            finish(bitmap, orientation + extraRotation, cropFraction, role, name)
        }

    /** Re-processes an already processed image (e.g. after the user rotates or crops it). */
    suspend fun reprocess(image: ProcessedImage, extraRotation: Int, cropFraction: Float, name: String): ProcessedImage =
        fromFile(image.file, image.payload.role, extraRotation, cropFraction, name)

    fun delete(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun decodeSampled(boundsStream: InputStream, dataStream: InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(boundsStream, null, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxEdgePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return dataStream.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IllegalArgumentException("Unsupported image")
    }

    private fun exifRotation(stream: InputStream): Int = runCatching {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun finish(source: Bitmap, rotation: Int, cropFraction: Float, role: ImageRole, name: String): ProcessedImage {
        var bitmap = source
        val degrees = ((rotation % 360) + 360) % 360
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        val crop = cropFraction.coerceIn(0.4f, 1f)
        if (crop < 1f) {
            val w = (bitmap.width * crop).roundToInt()
            val h = (bitmap.height * crop).roundToInt()
            bitmap = Bitmap.createBitmap(bitmap, (bitmap.width - w) / 2, (bitmap.height - h) / 2, w, h)
        }
        val longest = max(bitmap.width, bitmap.height)
        if (longest > maxEdgePx) {
            val scale = maxEdgePx.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.jpg").apply { writeBytes(bytes) }
        return ProcessedImage(ImagePayload(bytes, "image/jpeg", bitmap.width, bitmap.height, role, hash), file)
    }
}
