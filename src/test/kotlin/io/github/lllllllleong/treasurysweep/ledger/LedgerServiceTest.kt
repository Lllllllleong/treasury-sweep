package io.github.lllllllleong.treasurysweep.ledger

import io.github.lllllllleong.treasurysweep.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// No @Transactional on this class: it would wrap each test in one transaction and hide real commit and locking behaviour.
// Each test makes its own accounts, so tests don't share balances. platform:bank is shared, so never assert its totals.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class LedgerServiceTest(
    @Autowired private val ledger: LedgerService,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `collection credits the customer and returns Posted`() {
        val customer = newCustomerCashAccount()

        val result = collect(customer, "100.00")

        assertIs<TransferResult.Posted>(result)
        assertEquals(0L to 10000L, totals(customer))
    }

    @Test
    fun `posted transfer row records both accounts, the amount and the kind`() {
        val customer = newCustomerCashAccount()

        val result = assertIs<TransferResult.Posted>(collect(customer, "125.50"))

        assertEquals(
            TransferRow(platformBankId(), customer, 12550L, "COLLECTION"),
            transferRow(result.transferId),
        )
    }

    @Test
    fun `card spend within the balance is posted`() {
        val customer = newCustomerCashAccount()
        collect(customer, "100.00")

        val result = spend(customer, "30.00")

        assertIs<TransferResult.Posted>(result)
        assertEquals(3000L to 10000L, totals(customer))
    }

    @Test
    fun `spending exactly the balance is posted`() {
        val customer = newCustomerCashAccount()
        collect(customer, "100.00")

        val result = spend(customer, "100.00")

        assertIs<TransferResult.Posted>(result)
        assertEquals(10000L to 10000L, totals(customer))
    }

    @Test
    fun `overspend is declined and writes nothing`() {
        val customer = newCustomerCashAccount()
        collect(customer, "100.00")

        val result = spend(customer, "100.01")

        assertEquals(TransferResult.Declined(DeclineReason.INSUFFICIENT_FUNDS), result)
        assertEquals(0L, transfersDebiting(customer))
        assertEquals(0L to 10000L, totals(customer))
    }

    @Test
    fun `an account without the limit flag can go past its credits`() {
        val unlimited = newAccount(limited = false)
        val customer = newCustomerCashAccount()

        val result = ledger.transfer(unlimited, customer, Money.parse("50.00"), TransferKind.COLLECTION)

        assertIs<TransferResult.Posted>(result)
        assertEquals(5000L to 0L, totals(unlimited))
    }

    @Test
    fun `rejects a transfer from an account to itself`() {
        val customer = newCustomerCashAccount()

        assertFailsWith<IllegalArgumentException> {
            ledger.transfer(customer, customer, Money.parse("1.00"), TransferKind.CARD_SPEND)
        }
    }

    @Test
    fun `rejects a zero amount`() {
        val customer = newCustomerCashAccount()

        assertFailsWith<IllegalArgumentException> {
            ledger.transfer(platformBankId(), customer, Money.ZERO, TransferKind.COLLECTION)
        }
    }

    @Test
    fun `transfer to an unknown account fails and leaves the other account untouched`() {
        val customer = newCustomerCashAccount()

        assertFailsWith<EmptyResultDataAccessException> {
            ledger.transfer(customer, Long.MAX_VALUE, Money.parse("1.00"), TransferKind.CARD_SPEND)
        }
        assertEquals(0L to 0L, totals(customer))
    }

    // --- helpers (fixtures) ---

    private fun collect(customerAccountId: Long, amount: String): TransferResult =
        ledger.transfer(platformBankId(), customerAccountId, Money.parse(amount), TransferKind.COLLECTION)

    private fun spend(customerAccountId: Long, amount: String): TransferResult =
        ledger.transfer(customerAccountId, platformBankId(), Money.parse(amount), TransferKind.CARD_SPEND)

    private fun newCustomerCashAccount(): Long {
        val customerId = jdbc.sql("INSERT INTO customers (name) VALUES ('Test customer') RETURNING id")
            .query { rs, _ -> rs.getLong(1) }
            .single()
        return jdbc.sql(
            """
            INSERT INTO accounts (code, customer_id, normal_balance, debits_must_not_exceed_credits)
            VALUES (:code, :customerId, 'CREDIT', true)
            RETURNING id
            """
        )
            .param("code", "customer:$customerId:cash")
            .param("customerId", customerId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
    }

    /** A standalone account with no customer, for testing the limit flag in isolation. */
    private fun newAccount(limited: Boolean): Long =
        jdbc.sql(
            """
            INSERT INTO accounts (code, normal_balance, debits_must_not_exceed_credits)
            VALUES (:code, 'DEBIT', :limited)
            RETURNING id
            """
        )
            .param("code", "test:${UUID.randomUUID()}")
            .param("limited", limited)
            .query { rs, _ -> rs.getLong(1) }
            .single()

    private fun platformBankId(): Long =
        jdbc.sql("SELECT id FROM accounts WHERE code = 'platform:bank'")
            .query { rs, _ -> rs.getLong(1) }
            .single()

    /** (debits_posted, credits_posted) in cents. */
    private fun totals(accountId: Long): Pair<Long, Long> =
        jdbc.sql("SELECT debits_posted, credits_posted FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query { rs, _ -> rs.getLong("debits_posted") to rs.getLong("credits_posted") }
            .single()

    private fun transfersDebiting(accountId: Long): Long =
        jdbc.sql("SELECT count(*) FROM transfers WHERE debit_account_id = :id")
            .param("id", accountId)
            .query { rs, _ -> rs.getLong(1) }
            .single()

    private data class TransferRow(val debitAccountId: Long, val creditAccountId: Long, val amount: Long, val kind: String)

    private fun transferRow(id: Long): TransferRow =
        jdbc.sql("SELECT debit_account_id, credit_account_id, amount, kind FROM transfers WHERE id = :id")
            .param("id", id)
            .query { rs, _ ->
                TransferRow(
                    rs.getLong("debit_account_id"),
                    rs.getLong("credit_account_id"),
                    rs.getLong("amount"),
                    rs.getString("kind"),
                )
            }
            .single()
}
