package de.kalorienkompass.ai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo ready for the AI: JPEG bytes plus the decoded bitmap (for the preview in the UI). */
class PreparedPhoto(val jpeg: ByteArray, val bitmap: Bitmap) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Prepares a photo for the AI: decodes the Uri (ImageDecoder on API 28+, BitmapFactory below),
 * applies the EXIF orientation, scales the longest side to ≤ [MAX_LONG_SIDE] px and encodes it
 * as JPEG with quality [JPEG_QUALITY].
 *
 * ImageDecoder applies the EXIF orientation itself (its reported size is already rotated); on the
 * BitmapFactory path the orientation is read with androidx ExifInterface and applied manually.
 */
object ImagePreprocessor {

    const val MAX_LONG_SIDE: Int = 1568
    const val JPEG_QUALITY: Int = 85

    /** Runs on [Dispatchers.IO]. Throws [AiException] (kind [AiErrorKind.BAD_IMAGE]) with a German message. */
    suspend fun prepare(context: Context, uri: Uri): PreparedPhoto = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        try {
            val decoded = decode(resolver, uri)
            val bitmap = withoutAlpha(decoded)
            val out = ByteArrayOutputStream(256 * 1024)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw AiException(UNREADABLE, kind = AiErrorKind.BAD_IMAGE)
            }
            PreparedPhoto(out.toByteArray(), bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw AiException("Das Foto ist zu groß und konnte nicht verarbeitet werden.", e, AiErrorKind.BAD_IMAGE)
        } catch (e: FileNotFoundException) {
            throw AiException("Das Foto konnte nicht geöffnet werden.", e, AiErrorKind.BAD_IMAGE)
        } catch (e: SecurityException) {
            throw AiException("Kein Zugriff auf das Foto. Bitte wähle es erneut aus.", e, AiErrorKind.BAD_IMAGE)
        } catch (e: Exception) {
            throw AiException(UNREADABLE, e, AiErrorKind.BAD_IMAGE)
        }
    }

    /** Target size so that the longest side is ≤ [maxLongSide] (aspect ratio kept, never upscaled). */
    fun targetSize(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "invalid size" }
        val longest = max(width, height)
        if (longest <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longest
        return (width * scale).roundToInt().coerceIn(1, maxLongSide) to
            (height * scale).roundToInt().coerceIn(1, maxLongSide)
    }

    /** Largest power-of-two sample size that keeps the longest side ≥ [maxLongSide] (memory-friendly decoding). */
    fun sampleSizeFor(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxLongSide) sample *= 2
        return sample
    }

    private const val UNREADABLE = "Das Foto konnte nicht gelesen werden. Bitte versuche es mit einem anderen Bild."

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return decodeWithImageDecoder(resolver, uri)
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                // e.g. an unusual format ImageDecoder rejects – BitmapFactory may still manage
            }
        }
        return decodeWithBitmapFactory(resolver, uri)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(resolver: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size // already oriented according to EXIF
            if (size.width > 0 && size.height > 0) {
                val (w, h) = targetSize(size.width, size.height)
                if (w != size.width || h != size.height) decoder.setTargetSize(w, h)
            }
        }
    }

    private fun decodeWithBitmapFactory(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AiException(
                "Dieses Bildformat wird nicht unterstützt. Bitte wähle ein JPEG- oder PNG-Foto.",
                kind = AiErrorKind.BAD_IMAGE,
            )
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw AiException(UNREADABLE, kind = AiErrorKind.BAD_IMAGE)

        val orientation = readExifOrientation(resolver, uri)
        return orientAndScale(sampled, orientation)
    }

    private fun readExifOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Applies the EXIF [orientation] and scales to ≤ [MAX_LONG_SIDE] in one pass. */
    private fun orientAndScale(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> Unit
        }
        val longest = max(source.width, source.height)
        if (longest > MAX_LONG_SIDE) {
            val scale = MAX_LONG_SIDE.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return source
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result != source) source.recycle()
        return result
    }

    /** JPEG has no transparency: draw transparent images (e.g. PNG screenshots) on white. */
    private fun withoutAlpha(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return opaque
    }
}
