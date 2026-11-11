package io.github.lllllllleong.treasurysweep.ledger

/**
 * The outcome of [LedgerService.transfer].
 *
 * A decline is a normal result, not an exception, so the transaction still commits
 * (S2 stores the idempotency record for a decline in the same transaction).
 */
sealed interface TransferResult {
    data class Posted(val transferId: Long) : TransferResult
    data class Declined(val reason: DeclineReason) : TransferResult
}

enum class DeclineReason {
    INSUFFICIENT_FUNDS,
}