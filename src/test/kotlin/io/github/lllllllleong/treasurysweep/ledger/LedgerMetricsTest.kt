package io.github.lllllllleong.treasurysweep.ledger

import com.jayway.jsonpath.JsonPath
import io.github.lllllllleong.treasurysweep.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
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
import kotlin.test.assertTrue

// The ledger metrics (spec section 9), driven over HTTP because the controller records them. Spring Boot's tests swap
// every metrics exporter for an in-memory registry; @AutoConfigureMetrics puts the Prometheus one back, so
// /actuator/prometheus works. It also changes the context's cache key, so this class gets its own context and container.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class LedgerMetricsTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `answered transfers are counted by kind and outcome, and timed by kind`() {
        val id = createCustomer()
        val key = newKey()
        val countsBefore = transferCounts()
        val timedBefore = timedCounts()

        postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }
        postMoney("/customers/$id/collections", "100.00", key).andExpect { status { isCreated() } }
        postMoney("/customers/$id/card-spend", "30.00").andExpect { status { isCreated() } }
        postMoney("/customers/$id/card-spend", "500.00").andExpect { status { isEqualTo(422) } }

        // Not answered as transfers, so not counted: a reused key, a missing key and an unknown customer.
        postMoney("/customers/$id/collections", "1.00", key).andExpect { status { isEqualTo(422) } }
        postMoney("/customers/$id/card-spend", "1.00", key = null).andExpect { status { isBadRequest() } }
        postMoney("/customers/999999999/card-spend", "1.00").andExpect { status { isNotFound() } }

        // Every kind and outcome is a key, including the ones still at zero: they were registered at startup.
        assertEquals(
            mapOf(
                "collection/posted" to 1.0,
                "collection/declined" to 0.0,
                "collection/replayed" to 1.0,
                "card_spend/posted" to 1.0,
                "card_spend/declined" to 1.0,
                "card_spend/replayed" to 0.0,
            ),
            transferCounts().mapValues { (series, count) -> count - countsBefore.getValue(series) },
        )
        assertEquals(
            mapOf("collection" to 2L, "card_spend" to 2L),
            timedCounts().mapValues { (kind, count) -> count - timedBefore.getValue(kind) },
        )
    }

    @Test
    fun `the Prometheus endpoint exposes the counter, the timer's buckets and the gauge`() {
        val lines = mvc.get("/actuator/prometheus")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
            .lines()

        assertHasLine(lines, """ledger_transfers_total{kind="card_spend",outcome="declined"} """)
        // Buckets (le = "less than or equal"), so Prometheus can compute p95 with histogram_quantile.
        assertHasLine(lines, """ledger_transfer_duration_seconds_bucket{kind="collection",le="+Inf"} """)
        assertHasLine(lines, """ledger_transfer_duration_seconds_count{kind="collection"} """)
        assertHasLine(lines, "ledger_imbalance ")
    }

    @Test
    fun `the imbalance gauge reads zero, and catches a total that is one cent off`() {
        assertEquals(0.0, imbalance())

        // Corrupt one cached total the way a bug would, then put it back. Nothing else in this context uses
        // platform:fund-payable until the fund block, and each test context has its own database.
        shiftFundPayableCredits(1)
        try {
            assertEquals(-1.0, imbalance())
        } finally {
            shiftFundPayableCredits(-1)
        }
        assertEquals(0.0, imbalance())
    }

    // --- helpers ---

    /** `ledger.transfers` by "kind/outcome". */
    private fun transferCounts(): Map<String, Double> =
        registry.get("ledger.transfers").counters()
            .associate { "${it.id.getTag("kind")}/${it.id.getTag("outcome")}" to it.count() }

    /** How many requests `ledger.transfer.duration` has timed, by kind. */
    private fun timedCounts(): Map<String, Long> =
        registry.get("ledger.transfer.duration").timers().associate { checkNotNull(it.id.getTag("kind")) to it.count() }

    private fun imbalance(): Double = registry.get("ledger.imbalance").gauge().value()

    private fun shiftFundPayableCredits(cents: Long) {
        jdbc.sql("UPDATE accounts SET credits_posted = credits_posted + :cents WHERE code = :code")
            .param("cents", cents)
            .param("code", AccountCodes.PLATFORM_FUND_PAYABLE)
            .update()
    }

    private fun assertHasLine(lines: List<String>, prefix: String) {
        assertTrue(lines.any { it.startsWith(prefix) }, "no line starts with $prefix")
    }

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

    private fun createCustomer(): Long {
        val body = postJson("/customers", """{"name":"Test customer"}""", key = null)
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return JsonPath.read<Number>(body, "$.id").toLong()
    }
}
