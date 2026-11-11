package io.github.lllllllleong.treasurysweep.ledger

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerService(private val jdbc: JdbcClient) {

    /**
     * Posts one transfer, or declines it if the debit account would go over its limit.
     * Everything runs in one transaction, and both account rows stay locked until it commits.
     */
    @Transactional
    fun transfer(debitAccountId: Long, creditAccountId: Long, amount: Money, kind: TransferKind): TransferResult {
        // Caller bugs, not customer errors: an overspend is a Declined below, not an exception.
        require(amount > Money.ZERO) { "transfer amount must be positive: $amount" }
        require(debitAccountId != creditAccountId) { "cannot transfer from account $debitAccountId to itself" }

        // Lock the lower id first, whichever side it is. Every transfer locking in the same order
        // means two opposite transfers can't deadlock.
        val first = lockAccount(minOf(debitAccountId, creditAccountId))
        val second = lockAccount(maxOf(debitAccountId, creditAccountId))
        val debit = if (first.id == debitAccountId) first else second
        val credit = if (first.id == creditAccountId) first else second

        // Return rather than throw: a RuntimeException would roll the transaction back,
        // and S2 needs a decline to commit together with its idempotency record.
        if (debit.debitsMustNotExceedCredits && debit.debitsPosted + amount > debit.creditsPosted) {
            return TransferResult.Declined(DeclineReason.INSUFFICIENT_FUNDS)
        }

        val transferId = jdbc.sql(
            """
            INSERT INTO transfers (debit_account_id, credit_account_id, amount, kind)
            VALUES (:debitAccountId, :creditAccountId, :amount, :kind)
            RETURNING id
            """
        )
            .param("debitAccountId", debit.id)
            .param("creditAccountId", credit.id)
            .param("amount", amount.cents)
            .param("kind", kind.name)
            .query { rs, _ -> rs.getLong(1) }
            .single()

        // Safe as read-modify-write because both rows are locked until commit.
        jdbc.sql("UPDATE accounts SET debits_posted = debits_posted + :amount WHERE id = :id")
            .param("amount", amount.cents)
            .param("id", debit.id)
            .update()
        jdbc.sql("UPDATE accounts SET credits_posted = credits_posted + :amount WHERE id = :id")
            .param("amount", amount.cents)
            .param("id", credit.id)
            .update()

        return TransferResult.Posted(transferId)
    }

    /** Locks the account row until the current transaction ends; other lockers wait. */
    private fun lockAccount(id: Long): LockedAccount =
        jdbc.sql(
            """
            SELECT id, debits_posted, credits_posted, debits_must_not_exceed_credits
            FROM accounts
            WHERE id = :id
            FOR UPDATE
            """
        )
            .param("id", id)
            .query { rs, _ ->
                LockedAccount(
                    id = rs.getLong("id"),
                    debitsPosted = Money(rs.getLong("debits_posted")),
                    creditsPosted = Money(rs.getLong("credits_posted")),
                    debitsMustNotExceedCredits = rs.getBoolean("debits_must_not_exceed_credits"),
                )
            }
            .single()

    private data class LockedAccount(
        val id: Long,
        val debitsPosted: Money,
        val creditsPosted: Money,
        val debitsMustNotExceedCredits: Boolean,
    )
}
