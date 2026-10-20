package app.nouri.camera

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Suspends until an ML Kit / Play services task finishes. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Live retail-barcode reader for the camera preview. Runs fully on-device
 * (bundled ML Kit model); frames are never stored or sent anywhere.
 */
class BarcodeAnalyzer(private val onBarcode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build(),
    )
    private val busy = AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.all(Char::isDigit) } }?.let(onBarcode) }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }

    fun close() = scanner.close()
}

/** On-device OCR used for nutrition labels before any AI provider is involved. */
object OnDeviceText {
    suspend fun read(context: Context, file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(file))).await().text
        } finally {
            recognizer.close()
        }
    }

    /** Barcodes in a still photo (gallery import in label mode). */
    suspend fun barcodes(context: Context, file: File): List<String> {
        val scanner = BarcodeScanning.getClient()
        return try {
            scanner.process(InputImage.fromFilePath(context, Uri.fromFile(file))).await()
                .mapNotNull { it.rawValue?.takeIf { v -> v.length in 8..14 && v.all(Char::isDigit) } }
        } finally {
            scanner.close()
        }
    }
}
