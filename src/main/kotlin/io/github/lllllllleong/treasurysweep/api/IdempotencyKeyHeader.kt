package io.github.lllllllleong.treasurysweep.api

import io.github.lllllllleong.treasurysweep.idempotency.IdempotencyKey

/** The Idempotency-Key request header, from the IETF draft "The Idempotency-Key HTTP Header Field". */
object IdempotencyKeyHeader {
    const val NAME = "Idempotency-Key"

    /**
     * Converts the raw header value. Missing, blank or too long is a 400, and because this runs before the service is
     * called, nothing is stored for it: the client can fix the request and retry with the same key.
     */
    fun parse(value: String?): IdempotencyKey {
        if (value == null) {
            throw InvalidRequestException("the $NAME header is required")
        }
        return try {
            IdempotencyKey(value)
        } catch (e: IllegalArgumentException) {
            throw InvalidRequestException(e.message ?: "invalid $NAME")
        }
    }
}
