package io.github.lllllllleong.treasurysweep.customer

import com.jayway.jsonpath.JsonPath
import io.github.lllllllleong.treasurysweep.TestcontainersConfiguration
import io.github.lllllllleong.treasurysweep.ledger.AccountCodes
import io.github.lllllllleong.treasurysweep.ledger.LedgerService
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

// The Idempotency-Key contract over HTTP (spec section 4): replays, reused keys, missing keys, and what isn't stored.
// The concurrent cases are in IdempotencyConcurrencyTest. The annotations match CustomerApiTest's, so both classes
// share one cached Spring context and one PostgreSQL container.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val ledger: LedgerService,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `a repeat gets the same status and body, and posts once`() {
        val id = createCustomer()
        val key = newKey()

        val first = postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }
        val repeat = postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }

        assertEquals(first.body(), repeat.body())
        assertCash(id, "100.00")
        assertEquals(1L, transferCount(id))
    }

    @Test
    fun `a declined spend replays its 422 even after a top-up`() {
        val id = createCustomer()
        val key = newKey()
        postMoney("/customers/$id/collections", "10.00").andExpect { status { isCreated() } }

        val declined = postMoney("/customers/$id/card-spend", "50.00", key).andExpect {
            status { isEqualTo(422) }
            jsonPath("$.code") { value("insufficient_funds") }
        }
        postMoney("/customers/$id/collections", "100.00").andExpect { status { isCreated() } }
        val retried = postMoney("/customers/$id/card-spend", "50.00", key).andExpect { status { isEqualTo(422) } }

        // The stored answer wins: a retry never re-runs the request, even though it would now succeed.
        assertEquals(declined.body(), retried.body())
        assertCash(id, "110.00")
    }

    @Test
    fun `the same key with a different amount is 422 and posts nothing`() {
        val id = createCustomer()
        val key = newKey()
        postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }

        postMoney("/customers/$id/collections", "50.00", key).andExpect {
            status { isEqualTo(422) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("idempotency_key_reused") }
        }
        assertCash(id, "100.00")
        assertEquals(1L, transferCount(id))
    }

    @Test
    fun `the same key on the other endpoint is 422`() {
        val id = createCustomer()
        val key = newKey()
        postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }

        postMoney("/customers/$id/card-spend", "100.00", key).andExpect {
            status { isEqualTo(422) }
            jsonPath("$.code") { value("idempotency_key_reused") }
        }
        assertCash(id, "100.00")
    }

    @Test
    fun `JSON formatting doesn't make a different request`() {
        val id = createCustomer()
        val key = newKey()

        val first = postJson("/customers/$id/collections", """{"amount":"10.00","currency":"AUD"}""", key)
            .andExpect { status { isCreated() } }
        // Different field order, spacing and amount format; the same kind and cents, so the same fingerprint.
        val reformatted = postJson("/customers/$id/collections", """{ "currency": "AUD", "amount": "10" }""", key)
            .andExpect { status { isCreated() } }

        assertEquals(first.body(), reformatted.body())
        assertCash(id, "10.00")
    }

    @Test
    fun `keys are scoped per customer`() {
        val customerA = createCustomer()
        val customerB = createCustomer()
        val key = newKey()

        postMoney("/customers/$customerA/collections", "10.00", key).andExpect { status { isCreated() } }
        postMoney("/customers/$customerB/collections", "10.00", key).andExpect { status { isCreated() } }

        assertCash(customerA, "10.00")
        assertCash(customerB, "10.00")
    }

    @ParameterizedTest
    @ValueSource(strings = ["collections", "card-spend"])
    fun `a missing key is 400 and posts nothing`(endpoint: String) {
        val id = createCustomer()

        postMoney("/customers/$id/$endpoint", "10.00", key = null).andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("invalid_request") }
            jsonPath("$.detail") { value("the Idempotency-Key header is required") }
        }
        assertEquals(0L, transferCount(id))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a blank key is 400`(key: String) {
        val id = createCustomer()

        postMoney("/customers/$id/collections", "10.00", key).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_request") }
        }
        assertEquals(0L, transferCount(id))
    }

    @Test
    fun `a key can be up to 255 characters`() {
        val id = createCustomer()

        // 255 also has to get past the length CHECK in V2__idempotency_keys.sql, or this is a 500.
        postMoney("/customers/$id/collections", "10.00", "k".repeat(255)).andExpect { status { isCreated() } }
        postMoney("/customers/$id/collections", "10.00", "k".repeat(256)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_request") }
        }
        assertCash(id, "10.00")
    }

    @Test
    fun `a 400 isn't stored, so the corrected request can reuse the key`() {
        val id = createCustomer()
        val key = newKey()

        postMoney("/customers/$id/collections", "12.345", key).andExpect { status { isBadRequest() } }
        postMoney("/customers/$id/collections", "12.34", key).andExpect { status { isCreated() } }

        assertCash(id, "12.34")
    }

    // --- helpers ---

    private fun postJson(path: String, json: String, key: String?): ResultActionsDsl =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (key != null) header("Idempotency-Key", key)
        }

    /** Sends a fresh key unless told otherwise; `key = null` sends no header at all. */
    private fun postMoney(path: String, amount: String, key: String? = newKey()): ResultActionsDsl =
        postJson(path, """{"amount":"$amount","currency":"AUD"}""", key)

    private fun newKey() = UUID.randomUUID().toString()

    private fun ResultActionsDsl.body(): String = andReturn().response.contentAsString

    private fun createCustomer(): Long {
        val body = postJson("/customers", """{"name":"Test customer"}""", key = null)
            .andExpect { status { isCreated() } }
            .body()
        return JsonPath.read<Number>(body, "$.id").toLong()
    }

    private fun assertCash(customerId: Long, amount: String) {
        mvc.get("/customers/$customerId").andExpect {
            status { isOk() }
            jsonPath("$.cash.amount") { value(amount) }
        }
    }

    /** Transfers into or out of the customer's cash account. */
    private fun transferCount(customerId: Long): Long {
        val cashAccountId = checkNotNull(ledger.findAccount(AccountCodes.customerCash(customerId))).id
        return jdbc.sql("SELECT count(*) FROM transfers WHERE :id IN (debit_account_id, credit_account_id)")
            .param("id", cashAccountId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
    }
}
