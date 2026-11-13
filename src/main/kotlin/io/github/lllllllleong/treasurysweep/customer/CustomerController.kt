package io.github.lllllllleong.treasurysweep.customer

import io.github.lllllllleong.treasurysweep.api.IdempotencyKeyHeader
import io.github.lllllllleong.treasurysweep.api.MoneyJson
import io.github.lllllllleong.treasurysweep.api.problem
import io.github.lllllllleong.treasurysweep.idempotency.IdempotentResult
import io.github.lllllllleong.treasurysweep.ledger.DeclineReason
import io.github.lllllllleong.treasurysweep.ledger.LedgerMetrics
import io.github.lllllllleong.treasurysweep.ledger.TransferKind
import io.github.lllllllleong.treasurysweep.ledger.TransferOutcome
import io.github.lllllllleong.treasurysweep.ledger.TransferResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import kotlin.time.measureTimedValue

data class CreateCustomerRequest(@NotBlank val name: String)

data class CustomerResponse(val id: Long, val name: String, val cash: MoneyJson)

data class TransferResponse(val transferId: Long)

@RestController
@RequestMapping("/customers")
class CustomerController(private val customers: CustomerService, private val metrics: LedgerMetrics) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateCustomerRequest): ResponseEntity<CustomerResponse> {
        val customer = customers.create(request.name)
        return ResponseEntity.created(URI.create("/customers/${customer.id}")).body(customer.toResponse())
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): CustomerResponse = customers.get(id).toResponse()

    // The header is optional to Spring so that a missing key gets our 400 (with a code), not Spring's own.
    @PostMapping("/{id}/collections")
    fun collect(
        @PathVariable id: Long,
        @RequestHeader(IdempotencyKeyHeader.NAME, required = false) idempotencyKey: String?,
        @RequestBody amount: MoneyJson,
    ): ResponseEntity<*> {
        val key = IdempotencyKeyHeader.parse(idempotencyKey)
        val money = amount.toPositiveMoney()
        return answer(TransferKind.COLLECTION) { customers.collect(id, key, money) }
    }

    @PostMapping("/{id}/card-spend")
    fun spendOnCard(
        @PathVariable id: Long,
        @RequestHeader(IdempotencyKeyHeader.NAME, required = false) idempotencyKey: String?,
        @RequestBody amount: MoneyJson,
    ): ResponseEntity<*> {
        val key = IdempotencyKeyHeader.parse(idempotencyKey)
        val money = amount.toPositiveMoney()
        return answer(TransferKind.CARD_SPEND) { customers.spendOnCard(id, key, money) }
    }

    /**
     * Runs a transfer request and records it in the ledger metrics. The services are @Transactional, so by the time
     * [request] returns its transaction has committed, and a transfer that rolled back is never counted. Requests that
     * throw (404, a reused key, a 500) aren't counted here; Spring's http.server.requests metric has them, by status.
     */
    private fun answer(kind: TransferKind, request: () -> IdempotentResult): ResponseEntity<*> {
        val (answered, took) = measureTimedValue(request)
        metrics.recordTransfer(kind, answered.outcome, took)
        return toResponse(answered.result)
    }

    private fun toResponse(result: TransferResult): ResponseEntity<*> = when (result) {
        is TransferResult.Posted ->
            ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse(result.transferId))
        is TransferResult.Declined ->
            ResponseEntity.of(problem(HttpStatus.UNPROCESSABLE_CONTENT, result.reason.code, result.reason.detail)).build<Any>()
    }

    private fun Customer.toResponse() = CustomerResponse(id, name, MoneyJson.of(cash))

    private val IdempotentResult.outcome: TransferOutcome
        get() = if (replayed) TransferOutcome.REPLAYED else when (result) {
            is TransferResult.Posted -> TransferOutcome.POSTED
            is TransferResult.Declined -> TransferOutcome.DECLINED
        }

    private val DeclineReason.code: String
        get() = name.lowercase()

    private val DeclineReason.detail: String
        get() = when (this) {
            DeclineReason.INSUFFICIENT_FUNDS -> "the cash balance is too low for this amount"
        }
}
