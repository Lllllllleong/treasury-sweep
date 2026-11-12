package io.github.lllllllleong.treasurysweep.idempotency

import io.github.lllllllleong.treasurysweep.ledger.DeclineReason
import io.github.lllllllleong.treasurysweep.ledger.TransferResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Types

/** The client sent a different request with an Idempotency-Key it had already used. */
class IdempotencyKeyReusedException(key: IdempotencyKey) :
    RuntimeException("Idempotency-Key \"${key.value}\" was already used for a different request")

@Service
class IdempotencyService(private val jdbc: JdbcClient) {

    /**
     * Runs [transfer] at most once per customer and key. A repeat of the same request gets the stored result without
     * running it again (a replay); a different request with the same key throws [IdempotencyKeyReusedException].
     *
     * MANDATORY: the caller's transaction must already be open, so the key commits together with the transfer or not
     * at all. Without one, the claim would autocommit on its own and could outlive a failed transfer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun once(
        customerId: Long,
        key: IdempotencyKey,
        fingerprint: String,
        transfer: () -> TransferResult,
    ): TransferResult {
        if (claim(customerId, key, fingerprint)) {
            return transfer().also { record(customerId, key, it) }
        }
        val stored = find(customerId, key)
        if (stored.fingerprint != fingerprint) {
            throw IdempotencyKeyReusedException(key)
        }
        return checkNotNull(stored.result) { "Idempotency-Key \"${key.value}\" of customer $customerId has no result" }
    }

    /**
     * Inserts the key and returns true if this transaction now owns it. If another transaction holds the same key
     * uncommitted, the INSERT waits for it to finish: a commit turns this into DO NOTHING (false, so the caller
     * replays), and a rollback lets the insert go ahead.
     */
    private fun claim(customerId: Long, key: IdempotencyKey, fingerprint: String): Boolean =
        jdbc.sql(
            """
            INSERT INTO idempotency_keys (customer_id, idempotency_key, request_fingerprint)
            VALUES (:customerId, :key, :fingerprint)
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """
        )
            .param("customerId", customerId)
            .param("key", key.value)
            .param("fingerprint", fingerprint)
            .update() == 1

    private fun record(customerId: Long, key: IdempotencyKey, result: TransferResult) {
        val (transferId, declineReason) = when (result) {
            is TransferResult.Posted -> result.transferId to null
            is TransferResult.Declined -> null to result.reason.name
        }
        jdbc.sql(
            """
            UPDATE idempotency_keys
            SET transfer_id = :transferId, decline_reason = :declineReason
            WHERE customer_id = :customerId AND idempotency_key = :key
            """
        )
            // Typed, so Spring doesn't have to ask the driver what type a NULL is.
            .param("transferId", transferId, Types.BIGINT)
            .param("declineReason", declineReason, Types.VARCHAR)
            .param("customerId", customerId)
            .param("key", key.value)
            .update()
    }

    /**
     * Reads a key that another transaction committed. Under READ COMMITTED each statement takes a fresh snapshot, so
     * this sees the row the claim just waited for, even though it was committed after this transaction began.
     */
    private fun find(customerId: Long, key: IdempotencyKey): StoredRequest =
        jdbc.sql(
            """
            SELECT request_fingerprint, transfer_id, decline_reason
            FROM idempotency_keys
            WHERE customer_id = :customerId AND idempotency_key = :key
            """
        )
            .param("customerId", customerId)
            .param("key", key.value)
            .query { rs, _ ->
                // getObject gives null for SQL NULL, where getLong would give 0.
                val transferId: Long? = rs.getObject("transfer_id", Long::class.javaObjectType)
                val declineReason: String? = rs.getString("decline_reason")
                StoredRequest(
                    fingerprint = rs.getString("request_fingerprint"),
                    result = when {
                        transferId != null -> TransferResult.Posted(transferId)
                        declineReason != null -> TransferResult.Declined(DeclineReason.valueOf(declineReason))
                        else -> null
                    },
                )
            }
            .single()

    private data class StoredRequest(val fingerprint: String, val result: TransferResult?)
}
