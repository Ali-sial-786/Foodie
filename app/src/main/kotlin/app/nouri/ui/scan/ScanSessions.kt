package app.nouri.ui.scan

import app.nouri.image.ProcessedImage
import java.util.concurrent.ConcurrentHashMap

/** The three camera modes. Barcodes are read live inside [LABEL]. */
enum class ScanMode(val title: String, val hint: String) {
    MEAL("Scan meal", "Fit the whole plate in the frame"),
    INGREDIENTS("Scan ingredients", "Spread ingredients out so labels and shapes are visible"),
    LABEL("Label / barcode", "Point at a barcode, or photograph the nutrition table"),
}

/** Photos captured in one go, handed from the camera to the review screens. */
data class ScanCapture(
    val id: String,
    val mode: ScanMode,
    val images: List<ProcessedImage>,
    val createdAtEpochMs: Long,
    /** A barcode read live by the camera, if any. */
    val barcode: String? = null,
)

/**
 * Process-scoped hand-off between the camera and review screens. Kept out of
 * navigation arguments because photos are large; if the process dies the user
 * simply retakes (the files are cleaned up by retention maintenance).
 */
class ScanSessions {
    private val captures = ConcurrentHashMap<String, ScanCapture>()

    fun put(capture: ScanCapture) {
        captures[capture.id] = capture
    }

    operator fun get(id: String): ScanCapture? = captures[id]

    fun remove(id: String): ScanCapture? = captures.remove(id)
}
