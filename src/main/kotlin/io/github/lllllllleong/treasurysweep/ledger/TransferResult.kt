package io.github.lllllllleong.treasurysweep.ledger

/**
 * The outcome of [LedgerService.transfer].
 *
 * A decline is a normal result, not an exception, so the transaction still commits
 * (with the decline's idempotency record, so a retry gets the same answer).
 */
sealed interface TransferResult {
    data class Posted(val transferId: Long) : TransferResult
    data class Declined(val reason: DeclineReason) : TransferResult
}

enum class DeclineReason {
    INSUFFICIENT_FUNDS,
}