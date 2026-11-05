package de.kalorienkompass.ui.ai

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Camera photos for the AI live in cacheDir/ai_photos (shared with the camera app via FileProvider). */
internal object AiPhotoFiles {

    private const val DIRECTORY = "ai_photos"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** A new empty JPEG file; leftovers older than a few hours are removed. Call off the main thread. */
    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, DIRECTORY)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner $dir kann nicht angelegt werden")
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > MAX_AGE_MS) file.delete()
        }
        return File.createTempFile("mahlzeit_", ".jpg", dir)
    }

    /** content:// Uri for the camera app (authority as declared in the manifest). */
    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
