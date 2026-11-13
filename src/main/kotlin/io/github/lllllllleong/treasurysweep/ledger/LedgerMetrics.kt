package io.github.lllllllleong.treasurysweep.ledger

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** The `outcome` tag of `ledger.transfers`: what the client was told. */
enum class TransferOutcome {
    POSTED,
    DECLINED,
    REPLAYED,
}

/**
 * The ledger's metrics (spec section 9). Prometheus scrapes them at /actuator/prometheus, where `ledger.transfers`
 * becomes `ledger_transfers_total`, `ledger.transfer.duration` becomes `ledger_transfer_duration_seconds_*`, and
 * `ledger.imbalance` stays `ledger_imbalance`.
 */
@Component
class LedgerMetrics(private val registry: MeterRegistry, private val jdbc: JdbcClient) {

    init {
        // Every series starts at zero from the first scrape. A counter whose first sample is already 1 shows no increase
        // to rate(), and a missing series leaves a gap on the dashboard until that kind and outcome first happen.
        for (kind in TransferKind.entries) {
            TransferOutcome.entries.forEach { transfers(kind, it) }
            duration(kind)
        }

        // Read from the database on every scrape. Micrometer keeps only a weak reference to a gauge's state object, so
        // the state is this bean, which the Spring context keeps alive; a throwaway object would be garbage-collected
        // and the gauge would read NaN. If the query throws, Micrometer reports NaN, and the alert treats that as a
        // break too (see prometheus/alerts.yml).
        Gauge.builder("ledger.imbalance", this) { it.imbalanceCents().toDouble() }
            .description("Sum of debits minus sum of credits over all accounts, in cents; 0 unless the ledger is broken")
            .register(registry)
    }

    /**
     * Records one answered transfer request. Call it after the service's transaction has committed, so a transfer that
     * rolls back is never counted.
     */
    fun recordTransfer(kind: TransferKind, outcome: TransferOutcome, took: Duration) {
        transfers(kind, outcome).increment()
        duration(kind).record(took.toJavaDuration())
    }

    /**
     * Invariant I1: Σ debits minus Σ credits over every account, in cents. One statement sees one snapshot, and a
     * transfer updates both of its accounts in one transaction, so a transfer in flight is either wholly in the sums
     * or not in them at all: no false alarm mid-transfer.
     */
    fun imbalanceCents(): Long =
        jdbc.sql("SELECT coalesce(sum(debits_posted), 0) - coalesce(sum(credits_posted), 0) FROM accounts")
            .query { rs, _ -> rs.getLong(1) }
            .single()

    // register() returns the existing meter when the name and tags match, like Go's CounterVec.WithLabelValues.
    private fun transfers(kind: TransferKind, outcome: TransferOutcome): Counter =
        Counter.builder("ledger.transfers")
            .description("Transfer requests answered, by kind and outcome")
            .tag("kind", kind.tag)
            .tag("outcome", outcome.name.lowercase())
            .register(registry)

    // Tagged by kind only: each tag value multiplies the histogram's bucket series.
    private fun duration(kind: TransferKind): Timer =
        Timer.builder("ledger.transfer.duration")
            .description("Time to answer a transfer request, from the service call to its commit")
            .tag("kind", kind.tag)
            // Buckets, so Prometheus can work out p95 with histogram_quantile, summed across replicas. Without them it
            // only gets count, sum and max, and percentiles computed in the app can't be combined across instances.
            .publishPercentileHistogram()
            .minimumExpectedValue(java.time.Duration.ofMillis(1))
            .maximumExpectedValue(java.time.Duration.ofSeconds(10))
            .register(registry)

    private val TransferKind.tag: String
        get() = name.lowercase()
}
