# Treasury Sweep

A small backend service that keeps a business's cash near a target balance, sweeps the excess into a money market fund, and redeems fund units when spending needs the cash back. Every cash movement is recorded in a double-entry ledger, and fund holdings are reconciled with a simulated fund administrator at the close of each business day.

> **Status:** in progress, November 2026. Built so far: the ledger, the retry-safe API, and observability (Prometheus metrics, alert rules and a Grafana dashboard). Next: the fund block (unit registry, sweep rule and reconciliation). The design is in [docs/spec.md](docs/spec.md); decisions will be recorded in [docs/ADR.md](docs/ADR.md).

## Planned features

- **Ledger:** double-entry accounts and transfers, with a no-overdraft limit on customer cash.
- **Retry-safe API:** collection and card-spend endpoints that honour the `Idempotency-Key` header.
- **Unit registry:** money market fund holdings with daily forward pricing; redemptions settle two business days later.
- **Sweep rule:** keeps each customer's cash near its target.
- **Reconciliation:** day-close checks of the ledger and the unit registry against the fund administrator, with open breaks exposed as metrics and alerts.
- **Observability:** Prometheus metrics, a Grafana dashboard and alert rules.

## Planned stack

Kotlin, Spring Boot 4, PostgreSQL and Flyway, JUnit 5 and Testcontainers, Micrometer, Prometheus and Grafana, Docker Compose, and a local Kubernetes cluster.

## Running it

You need JDK 21 and Docker (Docker Desktop on Windows or macOS). The commands below are for a POSIX shell: Git Bash on Windows, or any shell on macOS and Linux.

```bash
docker compose up -d    # PostgreSQL, Prometheus and Grafana
./gradlew bootRun       # the app, on port 8080; Flyway creates the schema on first start
```

| What | Where |
|---|---|
| The API | http://localhost:8080 |
| The metrics, as Prometheus scrapes them | http://localhost:8080/actuator/prometheus |
| Prometheus: scrape targets and alerts | http://localhost:9090/targets and http://localhost:9090/alerts |
| Grafana: the ledger dashboard | http://localhost:3000/d/treasury-sweep-ledger |

Prometheus scrapes the app at port 8080, so nothing else can be using that port. Grafana opens without a login; that's acceptable only because every port is bound to 127.0.0.1.

### Try it

```bash
curl -i -X POST localhost:8080/customers -H 'Content-Type: application/json' -d '{"name":"Test Co"}'
curl -i -X POST localhost:8080/customers/1/collections -H 'Content-Type: application/json' -H 'Idempotency-Key: c-1' -d '{"amount":"100.00","currency":"AUD"}'
curl -i -X POST localhost:8080/customers/1/card-spend -H 'Content-Type: application/json' -H 'Idempotency-Key: s-1' -d '{"amount":"30.00","currency":"AUD"}'
curl -i localhost:8080/customers/1
```

Repeating a request with the same `Idempotency-Key` returns the first answer again without posting another transfer; the same key with a different amount gets a `422`.

For a busier dashboard, `scripts/traffic.sh [seconds] [base URL]` sends a mix of collections, card spends, declines, retries and a few client mistakes (two minutes against port 8080 by default).

### Watch the alert fire

Every transfer moves both sides of the ledger by the same amount, so `LedgerImbalance` never fires on its own. Change one cached total by a cent:

```bash
docker compose exec postgres psql -U treasury_sweep -c "UPDATE accounts SET credits_posted = credits_posted + 1 WHERE code = 'platform:fund-payable'"
```

Within a few seconds the dashboard's imbalance turns red and the alert is pending at http://localhost:9090/alerts; after a minute it fires. Run the same `UPDATE` with `- 1` to put the cent back.

### Stopping

Ctrl+C stops the app. `docker compose down` stops the containers and keeps the database; `docker compose down -v` deletes it too.

### Tests

`./gradlew build` compiles and runs every test against a throwaway PostgreSQL started by Testcontainers, so Docker has to be running. `./gradlew bootTestRun` starts the app against a throwaway database instead of the Compose one. CI also validates the Prometheus config and unit-tests the alert rules with `promtool` ([build.yml](.github/workflows/build.yml)).

## Approach

I'm using this project to learn Kotlin and Spring Boot. Claude Code helps with scaffolding, configuration and review (see [CLAUDE.md](CLAUDE.md)); the domain logic is written by hand.

## Disclaimer

Everything is simulated: the fund, its prices and the administrator are fakes. No real money, and not affiliated with any financial institution.
