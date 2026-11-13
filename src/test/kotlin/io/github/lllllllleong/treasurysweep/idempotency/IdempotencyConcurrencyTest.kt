package io.github.lllllllleong.treasurysweep.idempotency

import io.github.lllllllleong.treasurysweep.TestcontainersConfiguration
import io.github.lllllllleong.treasurysweep.customer.CustomerService
import io.github.lllllllleong.treasurysweep.ledger.AccountCodes
import io.github.lllllllleong.treasurysweep.ledger.LedgerService
import io.github.lllllllleong.treasurysweep.ledger.Money
import io.github.lllllllleong.treasurysweep.ledger.TransferResult
import io.github.lllllllleong.treasurysweep.runConcurrently
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Idempotency under concurrency, called at the service layer (spec section 10). No @Transactional on the class: these
// tests need real commits, and real waits between transactions.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class IdempotencyConcurrencyTest(
    @Autowired private val customers: CustomerService,
    @Autowired private val idempotency: IdempotencyService,
    @Autowired private val ledger: LedgerService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val pool = Executors.newCachedThreadPool()

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
    }

    @Test
    fun `20 concurrent identical requests post once, and every caller gets the same result`() {
        val customerId = customerWithCash("100.00")
        val key = newKey()

        val results = runConcurrently(20) { customers.spendOnCard(customerId, key, TEN) }

        val posted = assertIs<TransferResult.Posted>(results.first().result)
        assertEquals(List(20) { posted }, results.map { it.result })
        assertEquals(1, results.count { !it.replayed }, "one request ran; the other 19 replayed it")
        assertEquals(1L, spendCount(customerId))
        assertEquals(Money.parse("90.00"), customers.get(customerId).cash)
    }

    @Test
    fun `20 concurrent requests with one key and different amounts post once, and the rest are rejected`() {
        val customerId = customerWithCash("100.00")
        val key = newKey()

        // runCatching turns each call into a Result, like Go's (value, err), so the expected throws don't end the test.
        val results = runConcurrently(20) { i ->
            runCatching { customers.spendOnCard(customerId, key, Money.parse("${i + 1}.00")) }
        }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is IdempotencyKeyReusedException })
        assertEquals(1L, spendCount(customerId))
    }

    // Decision D5, made deterministic: the stress tests above could pass by luck, this one can't.
    @Test
    fun `a duplicate waits for the first request to commit, then replays its result`() {
        val customerId = customerWithCash("100.00")
        val key = newKey()
        val finishFirst = CountDownLatch(1)

        val first = runAndHoldOpen(finishFirst) { customers.spendOnCard(customerId, key, TEN) }
        val duplicate = pool.submit(Callable { customers.spendOnCard(customerId, key, TEN) })

        // The duplicate's INSERT is stuck behind the first request's uncommitted key row.
        assertFailsWith<TimeoutException> { duplicate.get(500, TimeUnit.MILLISECONDS) }

        finishFirst.countDown()
        val replay = duplicate.get(10, TimeUnit.SECONDS)
        assertEquals(first.get(10, TimeUnit.SECONDS)?.result, replay.result)
        assertTrue(replay.replayed)
        assertEquals(1L, spendCount(customerId))
    }

    @Test
    fun `if the first request rolls back, the duplicate runs as a new request`() {
        val customerId = customerWithCash("100.00")
        val key = newKey()
        val finishFirst = CountDownLatch(1)

        val first = runAndHoldOpen(finishFirst, rollBack = true) { customers.spendOnCard(customerId, key, TEN) }
        val duplicate = pool.submit(Callable { customers.spendOnCard(customerId, key, TEN) })
        assertFailsWith<TimeoutException> { duplicate.get(500, TimeUnit.MILLISECONDS) }

        finishFirst.countDown()
        val rolledBack = first.get(10, TimeUnit.SECONDS)?.result
        val retried = duplicate.get(10, TimeUnit.SECONDS)
        val posted = assertIs<TransferResult.Posted>(retried.result)

        assertFalse(retried.replayed, "the duplicate ran as a new request")
        assertNotEquals(rolledBack, posted, "a new transfer, not a replay of the rolled-back one")
        assertEquals(1L, spendCount(customerId))
        assertEquals(
            IdempotentResult(posted, replayed = true),
            customers.spendOnCard(customerId, key, TEN),
            "the key now replays the new transfer",
        )
    }

    @Test
    fun `once refuses to run without the caller's transaction`() {
        // The proxy checks before the method body runs, so this customer and key never reach the database.
        assertFailsWith<IllegalTransactionStateException> {
            idempotency.once(1, newKey(), "CARD_SPEND:1000") { error("must not run") }
        }
    }

    // --- helpers ---

    /**
     * Runs [request] on another thread inside a transaction and waits until it has run, leaving the transaction open.
     * Counting down [finish] ends it: a commit, or a rollback if [rollBack].
     */
    private fun runAndHoldOpen(
        finish: CountDownLatch,
        rollBack: Boolean = false,
        request: () -> IdempotentResult,
    ): Future<IdempotentResult?> {
        val ran = CountDownLatch(1)
        val future = pool.submit(Callable {
            tx.execute { status ->
                request().also {
                    ran.countDown()
                    finish.await()
                    if (rollBack) status.setRollbackOnly()
                }
            }
        })
        assertTrue(ran.await(10, TimeUnit.SECONDS), "the first request didn't run")
        return future
    }

    private fun customerWithCash(amount: String): Long {
        val id = customers.create("Test customer").id
        customers.collect(id, newKey(), Money.parse(amount))
        return id
    }

    private fun newKey() = IdempotencyKey(UUID.randomUUID().toString())

    private fun spendCount(customerId: Long): Long {
        val cashAccountId = checkNotNull(ledger.findAccount(AccountCodes.customerCash(customerId))).id
        return jdbc.sql("SELECT count(*) FROM transfers WHERE debit_account_id = :id")
            .param("id", cashAccountId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
    }

    private companion object {
        val TEN = Money.parse("10.00")
    }
}
