package rs.novica.dobarboiler.api

/**
 * Error hierarchy for the Ariston cloud client. Every [AristonApiClient] call throws
 * exactly one of these subtypes on failure (frozen contract, SPEC §2.2).
 */
sealed class AristonException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Re-login failed or the token was rejected twice (double 405). */
    class AuthFailed(message: String) : AristonException(message)

    /** HTTP 429 — DO NOT auto-retry; higher layers back off via WorkManager. */
    class RateLimited(message: String) : AristonException(message)

    /** 5xx / timeout / IO error that persisted after the single 5 s retry. */
    class Transient(message: String, cause: Throwable? = null) : AristonException(message, cause)

    /** HTTP 404 — callers usually map to null / "no data". */
    class NotFound : AristonException("404")
}
