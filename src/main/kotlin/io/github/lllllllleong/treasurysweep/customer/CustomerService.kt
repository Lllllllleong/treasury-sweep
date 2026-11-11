package io.github.lllllllleong.treasurysweep.customer

import io.github.lllllllleong.treasurysweep.ledger.Account
import io.github.lllllllleong.treasurysweep.ledger.AccountCodes
import io.github.lllllllleong.treasurysweep.ledger.LedgerService
import io.github.lllllllleong.treasurysweep.ledger.Money
import io.github.lllllllleong.treasurysweep.ledger.TransferKind
import io.github.lllllllleong.treasurysweep.ledger.TransferResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrNull

data class Customer(val id: Long, val name: String, val cash: Money)

class CustomerNotFoundException(customerId: Long) : RuntimeException("customer $customerId not found")

@Service
class CustomerService(
    private val jdbc: JdbcClient,
    private val ledger: LedgerService,
) {

    /** Creates the customer and its cash account in one transaction: both rows or neither. */
    @Transactional
    fun create(name: String): Customer {
        val id = jdbc.sql("INSERT INTO customers (name) VALUES (:name) RETURNING id")
            .param("name", name)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        ledger.openCustomerCashAccount(id)
        return Customer(id, name, Money.ZERO)
    }

    fun get(customerId: Long): Customer {
        val name = jdbc.sql("SELECT name FROM customers WHERE id = :id")
            .param("id", customerId)
            .query { rs, _ -> rs.getString(1) }
            .optional()
            .getOrNull()
            ?: throw CustomerNotFoundException(customerId)
        return Customer(customerId, name, cashAccount(customerId).balance)
    }

    /** Money received from the customer's own customers: Dr platform:bank, Cr customer cash. */
    fun collect(customerId: Long, amount: Money): TransferResult =
        ledger.transfer(platformBank().id, cashAccount(customerId).id, amount, TransferKind.COLLECTION)

    /** Card spend paid out of the customer's cash: Dr customer cash, Cr platform:bank. */
    fun spendOnCard(customerId: Long, amount: Money): TransferResult =
        ledger.transfer(cashAccount(customerId).id, platformBank().id, amount, TransferKind.CARD_SPEND)

    private fun cashAccount(customerId: Long): Account =
        ledger.findAccount(AccountCodes.customerCash(customerId)) ?: throw CustomerNotFoundException(customerId)

    private fun platformBank(): Account =
        checkNotNull(ledger.findAccount(AccountCodes.PLATFORM_BANK)) { "platform:bank is missing; V1__ledger.sql seeds it" }
}
