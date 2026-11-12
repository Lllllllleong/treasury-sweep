CREATE TABLE idempotency_keys
(
    customer_id         bigint      NOT NULL REFERENCES customers (id),
    idempotency_key     text        NOT NULL,
    request_fingerprint text        NOT NULL,
    transfer_id         bigint REFERENCES transfers (id),
    decline_reason      text,
    created_at          timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (customer_id, idempotency_key),
    CONSTRAINT idempotency_keys_key_length CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    -- Both outcome columns are NULL until the transfer is posted later in the same transaction (PostgreSQL can't
    -- defer a CHECK), so the database enforces "never both" and the code enforces "exactly one".
    CONSTRAINT idempotency_keys_one_outcome CHECK (transfer_id IS NULL OR decline_reason IS NULL)
);
