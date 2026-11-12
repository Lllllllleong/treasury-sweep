package io.github.lllllllleong.treasurysweep.idempotency

/**
 * The client's Idempotency-Key: the same key on a retry means "this is the same request". Opaque to us, so it's
 * compared exactly as sent. A value class so it can't be swapped with the request fingerprint, the other string
 * passed next to it.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Idempotency-Key must not be blank" }
        require(value.length <= MAX_LENGTH) { "Idempotency-Key must be at most $MAX_LENGTH characters" }
    }

    companion object {
        /** Matches the length CHECK in V2__idempotency_keys.sql. */
        const val MAX_LENGTH = 255
    }
}
