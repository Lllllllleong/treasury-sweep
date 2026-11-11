package io.github.lllllllleong.treasurysweep.ledger

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    // --- parse: valid ---

    @ParameterizedTest
    @CsvSource(
        "125.50,             12550",
        "125.5,              12550",
        "125,                12500",
        "0.01,               1",
        "0,                  0",
        "0.00,               0",
        "999999999999999.99, 99999999999999999",
    )
    fun `parses valid amounts to cents`(text: String, expectedCents: Long) {
        assertEquals(Money(expectedCents), Money.parse(text))
    }

    // --- parse: invalid ---

    @ParameterizedTest
    @ValueSource(
        strings = [
            "125.505",          // more than 2 dp
            "125.500",          // more than 2 dp, even though the value has only 2
            "-1",               // no signs
            "+1",
            ".5",               // digits needed on both sides of the point
            "5.",
            "1E+3",             // no scientific notation
            " 1.00",            // no whitespace
            "1.00 ",
            "1,000.00",         // no thousands separators
            "abc",
            "",
            "1.2.3",
            "1000000000000000", // 16 integer digits: over the cap
            "١٢٣",              // Arabic-Indic digits: BigDecimal accepts them, the regex must not
            "007.50",           // DECISION: strict = no leading zeros. Move to the valid list if you'd rather accept it.
        ]
    )
    fun `rejects invalid amounts`(text: String) {
        assertFailsWith<IllegalArgumentException> { Money.parse(text) }
    }

    @Test
    fun `error message includes the rejected input`() {
        val e = assertFailsWith<IllegalArgumentException> { Money.parse("12.345") }
        assertTrue("12.345" in e.message.orEmpty())
    }

    // --- constructor ---

    @Test
    fun `allows zero cents`() {
        assertEquals(0L, Money(0).cents)
    }

    @Test
    fun `rejects negative cents`() {
        assertFailsWith<IllegalArgumentException> { Money(-1) }
    }

    // --- toString ---

    @ParameterizedTest
    @CsvSource(
        "0,     0.00",
        "5,     0.05",
        "50,    0.50",
        "100,   1.00",
        "12550, 125.50",
    )
    fun `formats cents as a two-decimal string`(cents: Long, expected: String) {
        assertEquals(expected, Money(cents).toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.00", "0.01", "125.50", "999999999999999.99"])
    fun `canonical strings round-trip through parse and toString`(text: String) {
        assertEquals(text, Money.parse(text).toString())
    }

    // --- arithmetic ---

    @Test
    fun adds() {
        assertEquals(Money(150), Money(100) + Money(50))
    }

    @Test
    fun subtracts() {
        assertEquals(Money(50), Money(100) - Money(50))
    }

    @Test
    fun `subtracting to exactly zero is allowed`() {
        assertEquals(Money.ZERO, Money(100) - Money(100))
    }

    @Test
    fun `subtracting below zero throws`() {
        assertFailsWith<IllegalArgumentException> { Money(100) - Money(101) }
    }

    @Test
    fun `adding past Long range throws instead of wrapping`() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
    }

    @Test
    fun `compares by amount`() {
        assertTrue(Money(1) < Money(2))
        assertTrue(Money(2) > Money(1))
        assertEquals(0, Money(5).compareTo(Money(5)))
    }
}
