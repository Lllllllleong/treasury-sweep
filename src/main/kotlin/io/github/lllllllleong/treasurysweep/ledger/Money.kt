package io.github.lllllllleong.treasurysweep.ledger

import java.math.BigDecimal

/** A non-negative amount of AUD, stored as whole cents. */
@JvmInline
value class Money(val cents: Long) {
    init {
        require(cents >= 0) { "money cannot be negative: $cents cents" }
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    operator fun minus(other: Money): Money = Money(Math.subtractExact(cents, other.cents))

    operator fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    override fun toString(): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    companion object {
        val ZERO = Money(0)

        // Up to 15 integer digits with no leading zeros, then optionally 1 or 2 decimal places.
        // \d matches only ASCII 0-9 on the JVM. The digit cap keeps every match within Long range.
        private val FORMAT = Regex("""(0|[1-9]\d{0,14})(\.\d{1,2})?""")

        fun parse(text: String): Money {
            require(FORMAT.matches(text)) { "invalid amount: \"$text\"" }
            return Money(BigDecimal(text).movePointRight(2).longValueExact())
        }
    }
}
