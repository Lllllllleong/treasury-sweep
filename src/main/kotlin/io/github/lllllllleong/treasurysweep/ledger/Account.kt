package io.github.lllllllleong.treasurysweep.ledger

/** Stored in accounts.normal_balance as the enum name. */
enum class NormalBalance {
    DEBIT,
    CREDIT,
}

data class Account(
    val id: Long,
    val code: String,
    val normalBalance: NormalBalance,
    val debitsMustNotExceedCredits: Boolean,
    val debitsPosted: Money,
    val creditsPosted: Money,
) {
    /**
     * The balance on the account's normal side: credits minus debits for a credit-normal account, the reverse for a
     * debit-normal one. Throws if the balance has crossed to the other side, which no account in this ledger should do.
     */
    val balance: Money
        get() = when (normalBalance) {
            NormalBalance.CREDIT -> creditsPosted - debitsPosted
            NormalBalance.DEBIT -> debitsPosted - creditsPosted
        }
}

/** The chart of accounts from spec section 3. The platform accounts are seeded by V1__ledger.sql. */
object AccountCodes {
    const val PLATFORM_BANK = "platform:bank"
    const val PLATFORM_FUND_PAYABLE = "platform:fund-payable"

    fun customerCash(customerId: Long) = "customer:$customerId:cash"
}
