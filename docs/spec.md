# Treasury Sweep: design spec

Draft, 10 November 2026. Each feature is tagged with the tier it's planned in: **Must**, **Should**, **Could**, or **Won't** (out of scope for v1).

## 1. Problem

A business keeps money in a transactional cash account to pay for card spend and to receive collections from its own customers. Cash sitting idle earns nothing, but moving it into an investment by hand is tedious and easy to get wrong. Treasury Sweep:

1. keeps each business's cash near a target balance,
2. sweeps cash above the target into a money market fund, and redeems fund units when cash falls below it,
3. records every cash movement in a double-entry ledger, and
4. reconciles its records with the fund administrator after every business day.

## 2. Scope

**In v1:** one currency (AUD), one fund, business days Monday to Friday, a simulated clock and a simulated fund administrator.

**Won't (v1):** authentication, multiple currencies or FX, public holidays, lending or credit lines, a UI, real money, Temporal, Kafka.

## 3. Ledger (Must)

### Accounts

The ledger uses two-legged transfers in the style of TigerBeetle's debit/credit model: each transfer has one debit account, one credit account and one positive amount, so every transfer balances by construction.

| Account | What it represents | Normal balance | Limit |
|---|---|---|---|
| `customer:{id}:cash` | Cash owed to the customer (a liability) | Credit | Debits must not exceed credits: no overdraft |
| `platform:bank` | The bank account holding all customers' money (an asset) | Debit | None |
| `platform:fund-payable` | Cash committed to the fund but not yet paid (a liability) | Credit | None |

Each account caches `debits_posted` and `credits_posted` in cents. A credit-normal account's balance is `credits - debits`; a debit-normal account's is `debits - credits`.

### Transfers

| Event | Debit | Credit | Tier |
|---|---|---|---|
| Collection received | `platform:bank` | `customer:{id}:cash` | Must |
| Card spend | `customer:{id}:cash` | `platform:bank` | Must |
| Fund subscription submitted | `customer:{id}:cash` | `platform:fund-payable` | Should |
| Subscription cash paid to the fund (day close) | `platform:fund-payable` | `platform:bank` | Should |
| Redemption proceeds received (two business days later) | `platform:bank` | `customer:{id}:cash` | Should |

### Posting a transfer

In one database transaction: lock both account rows with `SELECT ... FOR UPDATE` in ascending id order (so two opposite transfers can't deadlock), check the debit account's limit, insert the transfer, update both accounts' cached totals, and commit. PostgreSQL's default READ COMMITTED isolation is enough because the rows are locked explicitly.

A rejected transfer is a normal outcome, not an exception: the service returns a sealed result (`Posted` or `Declined(reason)`), and the transaction still commits the idempotency record (section 4).

**Known limitation:** `platform:bank` takes part in every collection and card spend, so its row lock serialises all transfers (a hot account). That's fine for a demo. At scale, the options include deriving platform balances from transfers instead of caching them, splitting the house account into shards, or batching. Record this in the ADR for decision D2.

### Invariants

| # | Invariant | Checked by |
|---|---|---|
| I1 | Σ `debits_posted` = Σ `credits_posted` over all accounts | `ledger.imbalance` gauge (Must); reconciliation R1 |
| I2 | Each account's cached totals equal the sums of its transfers | Reconciliation R2 |
| I3 | No customer cash account has debits greater than credits | Row locks and the limit check; concurrency test |
| I4 | `platform:bank` balance = Σ customer cash balances + `platform:fund-payable` balance | Reconciliation R3 |
| I5 | A request repeated with the same `Idempotency-Key` posts at most one transfer | Idempotency tests |

I4 holds by construction (every event in the transfer table moves both sides by the same amount), so a break means a bug, which is exactly what a control is for.

## 4. API (Must)

| Method and path | Purpose |
|---|---|
| `POST /customers` | Create a customer and its cash account (sweep settings optional until the Should tier) |
| `POST /customers/{id}/collections` | Record money received. Requires `Idempotency-Key` |
| `POST /customers/{id}/card-spend` | Record card spend. Requires `Idempotency-Key`; `422 insufficient_funds` if it would overdraw |
| `GET /customers/{id}` | Cash balance; later also units held, fund value and pending orders |

**Money in JSON** is a decimal string plus a currency: `{"amount": "125.50", "currency": "AUD"}`. It must be positive with at most two decimal places, otherwise `400`. Strings keep floating point out of the whole path from client to database.

### Idempotency-Key

Follows the IETF draft *The Idempotency-Key HTTP Header Field* (draft-07, October 2025):

- Missing key on an endpoint that requires one: `400`.
- First request with a key: processed; the response status and body are stored with a hash of the method, path and body.
- Same key, same request: the stored response is returned and no new transfer is posted.
- Same key, different request: `422`, as the draft recommends.
- Same key while the first request is still running: the draft suggests `409`. v1 default: the second request waits on the unique index, then replays the stored response. Record the choice as decision D5.
- Stored: successes and business rejections such as insufficient funds, so a retry gets the same answer. Not stored: malformed requests (`400`), so the client can fix them and retry with the same key.
- Keys are unique per customer and are written in the same transaction as the transfer, so a crash can never leave a transfer without its key, or the reverse.

## 5. Fund and unit registry (Should)

### The fund

A fictional AUD money market fund with an accumulating unit class: income is reflected in the unit price instead of being paid out.

- **Prices:** NAV per unit to 6 decimal places, starting at 1.000000. Each business day D: `NAV_D = round_half_even(NAV_prev × (1 + y / 365) ^ d, 6)`, where `y` is the configured annual yield (for example 4.00%) and `d` is the number of calendar days since the previous business day, so Monday's price includes the weekend.
- **Forward pricing:** orders received on day D are priced at D's closing NAV, published at the close of D.
- **Units** to 4 decimal places.

### Orders

| | Subscription | Redemption |
|---|---|---|
| Customer gives | A cash amount | A number of units (the sweep converts cash to units at the latest NAV) |
| At submission | Dr `customer:{id}:cash`, Cr `platform:fund-payable`; order `SUBMITTED` | Units move from available to reserved |
| At the close of D | Units = `floor(amount / NAV_D, 4 dp)` credited; cash paid: Dr `platform:fund-payable`, Cr `platform:bank` | Reserved units removed; proceeds = `floor(units × NAV_D, 2 dp)`, due in two business days |
| Settlement | Same day | D + 2 business days: Dr `platform:bank`, Cr `customer:{id}:cash` |

Rounding always protects the fund's other investors: the fund never issues more units than were paid for, and never pays out more than the units are worth. The platform calculates the same numbers independently, so any disagreement with the administrator shows up in reconciliation.

**Holdings** per customer: available units, reserved units, and pending redemption proceeds with their settlement dates.

### Fake fund administrator

An in-process Kotlin implementation behind a `FundAdministrator` interface, keeping its own records:

- `submitOrder(clientOrderId, ...)`: idempotent on `clientOrderId`, so a retried submission never creates a second order.
- `close(date)`: publishes the NAV, prices and confirms every order received that day, and updates the **omnibus register**, the total units the platform holds for all its customers. The administrator sees only that total; the per-customer split lives in the platform's unit registry.
- `statement(date)`: omnibus units, the NAV and the confirmations for a date.
- **Failure injection** (configuration): `timeoutRate` makes `submitOrder` throw a timeout, sometimes after it has recorded the order, so retries have to be idempotent; `dropRate` makes the administrator accept an order and never confirm it, so reconciliation has something to catch.

The platform retries a timed-out submission up to three times with the same `clientOrderId`.

**Could:** put the fake behind HTTP (`/fake-admin/...`) and call it with Spring's `RestClient` and explicit timeouts, which is closer to a real integration.

## 6. Simulated clock and day close (Should)

The current business date lives in a `sim_clock` table, and all domain code reads it through an injected clock. `POST /sim/advance-day` closes day D and opens the next business day, in this order:

1. The administrator closes D: publishes `NAV_D`, then prices and confirms D's orders.
2. The platform applies the confirmations, idempotently: subscription units credited and cash paid; redemption units removed and proceeds scheduled.
3. Reconciliation runs as at D (section 8).
4. The date moves to the next business day, D'.
5. Redemption proceeds due on D' are posted.
6. The sweep rule runs for each customer, and its orders are submitted for D'.

## 7. Sweep rule (Should)

Each customer has a `targetCash` and a `minSweep`. The decision is a pure function, so it can be unit-tested without Spring or a database:

`decideSweep(cash, pendingProceeds, unitsAvailable, latestNav, targetCash, minSweep): SweepDecision`

- `excess = cash - targetCash`. If `excess >= minSweep`: `Subscribe(excess)`.
- `shortfall = targetCash - cash - pendingProceeds`. If `shortfall >= minSweep` and units are available: `Redeem(min(unitsAvailable, ceil(shortfall / latestNav, 4 dp)))`.
- Otherwise: `NoAction`.

`SweepDecision` is a sealed type, so a `when` over it has to handle every case.

**Known gap:** redemption proceeds take two business days to arrive, so card spend can be declined in the meantime. A real product might keep a bigger buffer or offer a credit line; both are out of scope.

## 8. Reconciliation (R1 Must as a gauge; R2 to R6 Should)

Runs at each day close, as at D:

| Check | Compares | Break when |
|---|---|---|
| R1 Ledger totals | Σ debits vs Σ credits over all accounts | They differ |
| R2 Account history | Each account's cached totals vs the sums of its transfers | Any account differs |
| R3 Customer money | `platform:bank` vs Σ customer cash + `platform:fund-payable` | They differ |
| R4 Units | Σ customer units (available + reserved) vs the administrator's omnibus units | They differ |
| R5 Orders | Platform orders for D vs administrator confirmations for D, matched on `clientOrderId` | An order is missing on either side, or the units or cash differ |
| R6 Cash in transit | `platform:fund-payable` after the close | It isn't zero |

A dropped order shows up in R5 (and, for a subscription, in R6). A confirmation applied twice shows up in R4.

Breaks are stored in a `recon_breaks` table (date, check, reference, expected, actual, status) and exposed through `GET /recon/breaks`, the `recon.breaks.open` gauge and a Prometheus alert.

## 9. Observability (Must for the ledger metrics; Should for the rest)

Micrometer metric names (the Prometheus registry adds suffixes, so `ledger.transfers` is scraped as `ledger_transfers_total`):

| Metric | Type | Tags | Tier |
|---|---|---|---|
| `ledger.transfers` | Counter | `kind`, `outcome` (posted, declined, replayed) | Must |
| `ledger.transfer.duration` | Timer | `kind` | Must |
| `ledger.imbalance` | Gauge, in cents (should be 0) | | Must |
| `fund.orders` | Counter | `side`, `status` | Should |
| `fund.admin.calls` | Counter | `outcome` (ok, timeout, error) | Should |
| `recon.breaks.open` | Gauge | `check` | Should |
| `sweep.decisions` | Counter | `action` | Should |

**Grafana** (dashboard provisioned from JSON in the repo): transfers per second by outcome, p95 transfer latency, ledger imbalance, open reconciliation breaks, fund administrator error rate.

**Prometheus alert rules:** `LedgerImbalance` (not zero for 1 minute; Must) and `ReconBreaksOpen` (above zero for 5 minutes; Should).

## 10. Testing (Must)

- Integration tests run against real PostgreSQL through Testcontainers, not H2, because locking and constraint behaviour has to match the real database.
- **Concurrency:** 50 parallel card spends of 10.00 against a balance of 200.00 give exactly 20 posted and 30 declined, and the balance never goes negative. Calling the service layer from a thread pool is enough.
- **Idempotency:** 20 parallel identical requests with one key post exactly one transfer, and all get the same response.
- Plain unit tests for the pure parts: money parsing, rounding, `decideSweep`.
- **Could:** a property-based test that random sequences of operations keep I1 to I4 true.

## 11. Running it

- **Docker Compose (Must):** PostgreSQL, Prometheus and Grafana. The app runs from Gradle or IntelliJ, and Prometheus scrapes it at `host.docker.internal:8080`.
- **Local Kubernetes (Could):** Docker Desktop's built-in cluster or kind. PostgreSQL as a Deployment and Service; the app as a Deployment with 2 replicas, a Service, a ConfigMap and a Secret, with liveness and readiness probes on Actuator's health groups. Two replicas show the idempotency and locking guarantees across pods: a duplicate request can land on a different pod and still post only once.

### Demo (about 5 minutes)

1. `docker compose up -d`, start the app, open the Grafana dashboard.
2. Create a customer with a target of 50,000.00 and a minimum sweep of 1,000.00, and record a 120,000.00 collection.
3. Send a card spend with an `Idempotency-Key`; send it again (replayed, still one transfer); send a different body with the same key (`422`).
4. Advance a day: the sweep subscribes 70,000.00. Advance again: the units are confirmed at that day's price.
5. Spend enough to drop below the target and advance: the sweep redeems units, and the proceeds arrive two business days later.
6. Set the drop rate to 1, let the sweep submit an order and advance: R5 records a break, the dashboard shows it, and the alert fires.
7. Run the concurrency test.

## 12. Open decisions

The defaults are what this spec assumes. Record each decision in `docs/ADR.md` once it's made, including why the alternatives lost.

| # | Decision | Default | Alternatives |
|---|---|---|---|
| D1 | Ledger model | Two-legged transfers | Multi-leg journal entries |
| D2 | Balances and concurrency | Cached totals, both rows locked in id order | Derive balances on read; conditional `UPDATE`; SERIALIZABLE with retries |
| D3 | Number types | `Long` cents in a value class; `BigDecimal` for units (4 dp) and NAV (6 dp) | `BigDecimal` everywhere; fixed-point `Long` units |
| D4 | Persistence | `JdbcClient` with Flyway | Spring Data JPA; Exposed; jOOQ |
| D5 | Duplicate request still in flight | Wait on the unique index, then replay | `409`, as the IETF draft suggests |
| D6 | Fund administrator boundary | In-process interface | HTTP with `RestClient` (Could) |
| D7 | Web stack | Spring MVC | WebFlux with coroutines; Vert.x |
| D8 | Rounding | Units and proceeds rounded down; NAV half-even | Any rule both sides share |

## References

- TigerBeetle, data modelling (the debit/credit model and balance limits): https://docs.tigerbeetle.com/coding/data-modeling/
- TigerBeetle, account flags: https://docs.tigerbeetle.com/reference/account/
- IETF, The Idempotency-Key HTTP Header Field (draft-07): https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header
