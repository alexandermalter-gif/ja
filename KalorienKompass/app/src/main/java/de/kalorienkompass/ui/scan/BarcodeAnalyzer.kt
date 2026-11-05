package de.kalorienkompass.ui.scan

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CameraX analyzer that feeds frames to the bundled ML Kit barcode model (EAN-13, EAN-8, UPC-A,
 * UPC-E). Every [ImageProxy] is closed – right away when analysis is paused, otherwise when ML Kit
 * has finished with it (which also throttles the analysis together with STRATEGY_KEEP_ONLY_LATEST).
 *
 * [onDetected] receives the normalized code (see [BarcodeUtils.normalizeScanned]) on the main
 * thread. [onFailure] is called once on the main thread if the detector keeps failing.
 * Call [close] when the analyzer is no longer used (releases the ML Kit detector).
 */
internal class BarcodeAnalyzer(
    private val onDetected: (String) -> Unit,
    private val onFailure: (Exception) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build(),
    )

    /** While false, frames are closed immediately without running the detector. */
    @Volatile
    var isEnabled: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val failureReported = AtomicBoolean(false)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (!isEnabled || closed.get() || mediaImage == null) {
            imageProxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    consecutiveFailures.set(0)
                    if (isEnabled && !closed.get()) {
                        bestCode(barcodes)?.let(onDetected)
                    }
                }
                .addOnFailureListener { e ->
                    if (!closed.get()) reportFailure(e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            // E.g. detector already closed or an unsupported image format: never leak the frame.
            imageProxy.close()
            if (!closed.get()) reportFailure(e)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            isEnabled = false
            try {
                scanner.close()
            } catch (e: Exception) {
                Log.w(TAG, "Closing the barcode scanner failed", e)
            }
        }
    }

    /** Counts a failed frame; reports a persistent problem once (always on the main thread). */
    private fun reportFailure(e: Exception) {
        Log.w(TAG, "Barcode detection failed", e)
        if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES &&
            failureReported.compareAndSet(false, true)
        ) {
            mainHandler.post {
                if (!closed.get()) onFailure(e)
            }
        }
    }

    /** The plausible retail code with the largest bounding box (the one the user aims at). */
    private fun bestCode(barcodes: List<Barcode>): String? {
        var best: String? = null
        var bestArea = -1L
        for (barcode in barcodes) {
            val code = BarcodeUtils.normalizeScanned(
                barcode.rawValue,
                isUpcE = barcode.format == Barcode.FORMAT_UPC_E,
            ) ?: continue
            val box = barcode.boundingBox
            val area = if (box != null) box.width().toLong() * box.height().toLong() else 0L
            if (area > bestArea) {
                best = code
                bestArea = area
            }
        }
        return best
    }

    private companion object {
        const val TAG = "BarcodeAnalyzer"
        const val MAX_CONSECUTIVE_FAILURES = 15
    }
}
