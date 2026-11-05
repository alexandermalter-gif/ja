package de.kalorienkompass.ai

import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.ContentTooLargeException
import com.anthropic.errors.CredentialResolutionException
import com.anthropic.errors.GatewayTimeoutException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NoCredentialsException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.RequestTimeoutException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Maps exceptions of the Anthropic SDK (and the network stack) to German, user-facing [AiException]s.
 * Usable by every AI feature of the app.
 */
object AiErrors {

    const val MISSING_KEY_MESSAGE = "Bitte zuerst einen API-Schlüssel in den Einstellungen hinterlegen."

    private const val NETWORK_MESSAGE =
        "Keine Verbindung zum KI-Dienst. Bitte prüfe deine Internetverbindung und versuche es erneut."
    private const val TIMEOUT_MESSAGE =
        "Die KI hat zu lange für die Antwort gebraucht. Bitte versuche es erneut."
    private const val BILLING_MESSAGE =
        "Dein Guthaben bei Anthropic reicht nicht aus. Bitte lade es auf console.anthropic.com unter „Billing“ auf."

    fun missingKey(cause: Throwable? = null): AiException =
        AiException(MISSING_KEY_MESSAGE, cause, AiErrorKind.MISSING_KEY)

    /** Removes [CompletionException]/[ExecutionException] wrappers of async calls. */
    fun unwrap(t: Throwable): Throwable {
        var current = t
        var depth = 0
        while ((current is CompletionException || current is ExecutionException) && depth < 8) {
            current = current.cause ?: break
            depth++
        }
        return current
    }

    /** German, user-facing [AiException] for any failure of an AI request. */
    fun from(t: Throwable): AiException {
        val e = unwrap(t)
        return when (e) {
            is AiException -> e
            is UnauthorizedException -> AiException(
                "Der API-Schlüssel ist ungültig oder wurde widerrufen. Bitte prüfe ihn in den Einstellungen.",
                e, AiErrorKind.INVALID_KEY,
            )
            is PermissionDeniedException -> AiException(
                "Dein API-Schlüssel darf diese Funktion nicht nutzen. Bitte prüfe deinen Zugang auf console.anthropic.com.",
                e, AiErrorKind.NO_ACCESS,
            )
            is NotFoundException -> AiException(
                "Das gewählte KI-Modell ist für deinen API-Schlüssel nicht verfügbar. Bitte wähle in den Einstellungen ein anderes Modell.",
                e, AiErrorKind.NO_ACCESS,
            )
            is RateLimitException -> AiException(
                "Zu viele Anfragen in kurzer Zeit. Bitte warte einen Moment und versuche es dann erneut.",
                e, AiErrorKind.RATE_LIMIT,
            )
            is ContentTooLargeException -> AiException(
                "Das Foto ist zu groß für die KI. Bitte versuche es mit einem anderen Foto.",
                e, AiErrorKind.BAD_IMAGE,
            )
            is BadRequestException -> badRequest(e)
            is OverloadedException -> AiException(
                "Die KI ist gerade überlastet. Bitte versuche es in ein paar Minuten erneut.",
                e, AiErrorKind.OVERLOADED,
            )
            is GatewayTimeoutException, is RequestTimeoutException -> AiException(TIMEOUT_MESSAGE, e, AiErrorKind.TIMEOUT)
            is InternalServerException -> AiException(
                "Beim KI-Dienst ist ein Fehler aufgetreten (${e.statusCode()}). Bitte versuche es später erneut.",
                e, AiErrorKind.SERVER,
            )
            is AnthropicServiceException -> otherServiceError(e)
            is AnthropicInvalidDataException -> AiException(
                "Die Antwort der KI konnte nicht gelesen werden. Bitte versuche es erneut.",
                e, AiErrorKind.BAD_RESPONSE,
            )
            is NoCredentialsException, is CredentialResolutionException -> missingKey(e)
            is AnthropicIoException, is AnthropicRetryableException, is IOException ->
                if (isTimeout(e)) {
                    AiException(TIMEOUT_MESSAGE, e, AiErrorKind.TIMEOUT)
                } else {
                    AiException(NETWORK_MESSAGE, e, AiErrorKind.NETWORK)
                }
            else -> AiException(
                "Unerwarteter Fehler bei der KI-Anfrage: ${e.message ?: e.javaClass.simpleName}",
                e, AiErrorKind.OTHER,
            )
        }
    }

    private fun badRequest(e: BadRequestException): AiException {
        val apiMessage = e.apiMessage().orElse(null)?.trim().orEmpty()
        if (isBillingProblem(e, apiMessage)) return AiException(BILLING_MESSAGE, e, AiErrorKind.BILLING)
        val text = if (apiMessage.isNotEmpty()) {
            "Die KI-Anfrage wurde abgelehnt: $apiMessage"
        } else {
            "Die KI-Anfrage wurde abgelehnt (Fehler 400)."
        }
        return AiException(text, e, AiErrorKind.BAD_REQUEST)
    }

    private fun otherServiceError(e: AnthropicServiceException): AiException {
        val apiMessage = e.apiMessage().orElse(null)?.trim().orEmpty()
        val type = e.errorType().orElse(null)
        return when {
            isBillingProblem(e, apiMessage) -> AiException(BILLING_MESSAGE, e, AiErrorKind.BILLING)
            type == ErrorType.OVERLOADED_ERROR -> AiException(
                "Die KI ist gerade überlastet. Bitte versuche es in ein paar Minuten erneut.",
                e, AiErrorKind.OVERLOADED,
            )
            type == ErrorType.RATE_LIMIT_ERROR -> AiException(
                "Zu viele Anfragen in kurzer Zeit. Bitte warte einen Moment und versuche es dann erneut.",
                e, AiErrorKind.RATE_LIMIT,
            )
            else -> {
                val detail = if (apiMessage.isNotEmpty()) ": $apiMessage" else "."
                AiException("Der KI-Dienst meldet einen Fehler (${e.statusCode()})$detail", e, AiErrorKind.SERVER)
            }
        }
    }

    /** "Your credit balance is too low …" arrives as HTTP 400 (invalid_request_error) or 402 (billing_error). */
    private fun isBillingProblem(e: AnthropicServiceException, apiMessage: String): Boolean =
        e.statusCode() == 402 ||
            e.errorType().orElse(null) == ErrorType.BILLING_ERROR ||
            apiMessage.contains("credit balance", ignoreCase = true)

    private fun isTimeout(t: Throwable): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 10) {
            if (current is InterruptedIOException) return true // includes SocketTimeoutException
            current = current.cause
            depth++
        }
        return false
    }
}
