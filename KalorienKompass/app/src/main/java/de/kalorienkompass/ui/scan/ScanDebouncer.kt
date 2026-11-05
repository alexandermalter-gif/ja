package de.kalorienkompass.ui.scan

/**
 * Suppresses repeated detections of the same barcode.
 *
 * The camera delivers many frames per second, so the same code is usually detected again and
 * again. Once a code has been handled it is ignored until it has been out of view for
 * [cooldownMillis]: every sighting while it is suppressed extends the window. Any other code is
 * accepted immediately.
 *
 * Not thread-safe – call it from one thread (the main thread).
 */
internal class ScanDebouncer(private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS) {

    private var lastCode: String? = null
    private var lastSeenAt: Long = 0L

    /** Returns true if [code] (seen at [now], monotonic millis) should be processed. */
    fun accept(code: String, now: Long): Boolean {
        val suppressed = code == lastCode && now - lastSeenAt < cooldownMillis
        lastCode = code
        lastSeenAt = now
        return !suppressed
    }

    /** Records [code] as handled (e.g. typed in manually) so the camera does not trigger it right away. */
    fun markHandled(code: String, now: Long) {
        lastCode = code
        lastSeenAt = now
    }

    /** Restarts the cooldown of the last handled code, e.g. when scanning resumes after a dialog. */
    fun restartCooldown(now: Long) {
        if (lastCode != null) lastSeenAt = now
    }

    /** Forgets the last code. */
    fun reset() {
        lastCode = null
        lastSeenAt = 0L
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS: Long = 2_500L
    }
}
