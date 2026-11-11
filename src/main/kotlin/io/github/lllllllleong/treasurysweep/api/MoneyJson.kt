package io.github.lllllllleong.treasurysweep.api

import io.github.lllllllleong.treasurysweep.ledger.Money

/** Money on the wire: a decimal string plus a currency, so no floating point anywhere between client and database. */
data class MoneyJson(val amount: String, val currency: String) {

    /** Converts a request amount, which must be a positive AUD amount; anything else is a 400. */
    fun toPositiveMoney(): Money {
        if (currency != CURRENCY) {
            throw InvalidRequestException("unsupported currency \"$currency\"; only $CURRENCY is supported")
        }
        val money = try {
            Money.parse(amount)
        } catch (e: IllegalArgumentException) {
            throw InvalidRequestException(e.message ?: "invalid amount")
        }
        if (money == Money.ZERO) {
            throw InvalidRequestException("amount must be positive")
        }
        return money
    }

    companion object {
        const val CURRENCY = "AUD"

        fun of(money: Money) = MoneyJson(money.toString(), CURRENCY)
    }
}
