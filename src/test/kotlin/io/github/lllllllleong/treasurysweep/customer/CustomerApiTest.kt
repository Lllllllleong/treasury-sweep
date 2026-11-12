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

// Drives the real HTTP layer (routing, JSON, validation, error mapping) through MockMvc, against PostgreSQL in
// Testcontainers. Each test creates its own customer, so tests don't share balances.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CustomerApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val ledger: LedgerService,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `collect 100, spend 30, balance 70, then spending 80 more is 422 and writes no transfer`() {
        val id = createCustomer()

        postMoney("/customers/$id/collections", "100.00").andExpect { status { isCreated() } }
        postMoney("/customers/$id/card-spend", "30.00").andExpect { status { isCreated() } }
        assertCash(id, "70.00")

        postMoney("/customers/$id/card-spend", "80.00").andExpect {
            status { isEqualTo(422) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("insufficient_funds") }
        }
        assertCash(id, "70.00")
        assertEquals(1L, transfersDebitingCash(id), "only the 30.00 spend debits the customer")
    }

    @Test
    fun `a posted transfer returns 201 with its id`() {
        val id = createCustomer()

        postMoney("/customers/$id/collections", "125.50").andExpect {
            status { isCreated() }
            jsonPath("$.transferId") { isNumber() }
        }
    }

    @Test
    fun `creating a customer returns 201, a Location header and a zero balance`() {
        val result = postJson("/customers", """{"name":"Test Co"}""").andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Test Co") }
            jsonPath("$.cash.amount") { value("0.00") }
            jsonPath("$.cash.currency") { value("AUD") }
        }.andReturn()

        val id = JsonPath.read<Number>(result.response.contentAsString, "$.id").toLong()
        assertEquals("/customers/$id", result.response.getHeader("Location"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"amount":"12.345","currency":"AUD"}""", // more than 2 dp
            """{"amount":"0.00","currency":"AUD"}""",   // zero
            """{"amount":"-5.00","currency":"AUD"}""",  // negative
            """{"amount":"10.00","currency":"USD"}""",  // wrong currency
            """{"amount":10.50,"currency":"AUD"}""",    // JSON number, not a string
            """{"amount":10,"currency":"AUD"}""",       // JSON integer, not a string
            """{"currency":"AUD"}""",                   // missing amount
            """{not json""",
        ]
    )
    fun `bad money is 400 and posts nothing`(body: String) {
        val id = createCustomer()

        postJson("/customers/$id/collections", body, newKey()).andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
        assertCash(id, "0.00")
    }

    @Test
    fun `invalid money explains itself`() {
        val id = createCustomer()

        postMoney("/customers/$id/collections", "12.345").andExpect {
            jsonPath("$.code") { value("invalid_request") }
            jsonPath("$.detail") { value("invalid amount: \"12.345\"") }
        }
    }

    @Test
    fun `blank customer name is 400`() {
        postJson("/customers", """{"name":"   "}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `unknown customer is 404 on read and on transfer`() {
        mvc.get("/customers/${Long.MAX_VALUE}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("customer_not_found") }
        }
        postMoney("/customers/${Long.MAX_VALUE}/card-spend", "1.00").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("customer_not_found") }
        }
    }

    @Test
    fun `non-numeric customer id is 400`() {
        mvc.get("/customers/abc").andExpect { status { isBadRequest() } }
    }

    // --- helpers ---

    private fun postJson(path: String, json: String, idempotencyKey: String? = null): ResultActionsDsl =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
        }

    /** A transfer with a fresh Idempotency-Key, so every call is a new request. IdempotencyApiTest covers the key. */
    private fun postMoney(path: String, amount: String): ResultActionsDsl =
        postJson(path, """{"amount":"$amount","currency":"AUD"}""", newKey())

    private fun newKey() = UUID.randomUUID().toString()

    private fun createCustomer(): Long {
        val body = postJson("/customers", """{"name":"Test customer"}""")
            .andExpect { status { isCreated() } }
            .andReturn()
            .response
            .contentAsString
        return JsonPath.read<Number>(body, "$.id").toLong()
    }

    private fun assertCash(customerId: Long, amount: String) {
        mvc.get("/customers/$customerId").andExpect {
            status { isOk() }
            jsonPath("$.cash.amount") { value(amount) }
            jsonPath("$.cash.currency") { value("AUD") }
        }
    }

    private fun transfersDebitingCash(customerId: Long): Long {
        val cashAccountId = checkNotNull(ledger.findAccount(AccountCodes.customerCash(customerId))).id
        return jdbc.sql("SELECT count(*) FROM transfers WHERE debit_account_id = :id")
            .param("id", cashAccountId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
    }
}
