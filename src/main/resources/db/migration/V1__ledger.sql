CREATE TABLE customers
(
    id         bigserial PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts
(
    id                             bigserial PRIMARY KEY,
    code                           text        NOT NULL UNIQUE,
    customer_id                    bigint REFERENCES customers (id),
    normal_balance                 text        NOT NULL,
    debits_must_not_exceed_credits boolean     NOT NULL,
    debits_posted                  bigint      NOT NULL DEFAULT 0,
    credits_posted                 bigint      NOT NULL DEFAULT 0,
    created_at                     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT accounts_normal_balance_valid CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT accounts_posted_non_negative CHECK (debits_posted >= 0 AND credits_posted >= 0)
);

CREATE TABLE transfers
(
    id                bigserial PRIMARY KEY,
    debit_account_id  bigint      NOT NULL REFERENCES accounts (id),
    credit_account_id bigint      NOT NULL REFERENCES accounts (id),
    amount            bigint      NOT NULL,
    kind              text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT transfers_distinct_accounts CHECK (debit_account_id <> credit_account_id)
);

CREATE INDEX transfers_debit_account_idx ON transfers (debit_account_id);
CREATE INDEX transfers_credit_account_idx ON transfers (credit_account_id);

INSERT INTO accounts (code, normal_balance, debits_must_not_exceed_credits)
VALUES ('platform:bank', 'DEBIT', false),
       ('platform:fund-payable', 'CREDIT', false);