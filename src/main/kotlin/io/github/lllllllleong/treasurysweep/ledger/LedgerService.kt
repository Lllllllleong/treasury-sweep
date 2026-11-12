package io.github.lllllllleong.treasurysweep.ledger

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrNull

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
        // and a decline has to commit together with its idempotency record.
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

    /**
     * Opens a customer's cash account: credit-normal (it's money owed to the customer) with no overdraft.
     * Runs in the caller's transaction, so the customer row and its account commit together.
     */
    fun openCustomerCashAccount(customerId: Long): Long =
        jdbc.sql(
            """
            INSERT INTO accounts (code, customer_id, normal_balance, debits_must_not_exceed_credits)
            VALUES (:code, :customerId, 'CREDIT', true)
            RETURNING id
            """
        )
            .param("code", AccountCodes.customerCash(customerId))
            .param("customerId", customerId)
            .query { rs, _ -> rs.getLong(1) }
            .single()

    fun findAccount(code: String): Account? =
        jdbc.sql("SELECT $ACCOUNT_COLUMNS FROM accounts WHERE code = :code")
            .param("code", code)
            .query(ACCOUNT_ROW)
            .optional()
            .getOrNull()

    /** Locks the account row until the current transaction ends; other lockers wait. */
    private fun lockAccount(id: Long): Account =
        jdbc.sql("SELECT $ACCOUNT_COLUMNS FROM accounts WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(ACCOUNT_ROW)
            .single()

    private companion object {
        const val ACCOUNT_COLUMNS =
            "id, code, normal_balance, debits_must_not_exceed_credits, debits_posted, credits_posted"

        val ACCOUNT_ROW = RowMapper { rs, _ ->
            Account(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                normalBalance = NormalBalance.valueOf(rs.getString("normal_balance")),
                debitsMustNotExceedCredits = rs.getBoolean("debits_must_not_exceed_credits"),
                debitsPosted = Money(rs.getLong("debits_posted")),
                creditsPosted = Money(rs.getLong("credits_posted")),
            )
        }
    }
}
