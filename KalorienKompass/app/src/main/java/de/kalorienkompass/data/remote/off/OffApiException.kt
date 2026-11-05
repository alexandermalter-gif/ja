package de.kalorienkompass.data.remote.off

import java.io.IOException

/**
 * Open Food Facts answered, but not usefully (HTTP error, rate limit, invalid JSON).
 * [message] is German and user-facing. Plain [IOException]s (no connection, timeout) are passed through.
 */
class OffApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
