package io.github.lllllllleong.treasurysweep.ledger

/** Stored in transfers.kind as the enum name. The fund kinds arrive in U1. */
enum class TransferKind {
    COLLECTION,
    CARD_SPEND,
}
