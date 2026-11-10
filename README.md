# Treasury Sweep

A small backend service that keeps a business's cash near a target balance, sweeps the excess into a money market fund, and redeems fund units when spending needs the cash back. Every cash movement is recorded in a double-entry ledger, and fund holdings are reconciled with a simulated fund administrator at the close of each business day.

> **Status:** planning, November 2026. Implementation starts 14 November 2026. The design is in [docs/spec.md](docs/spec.md); decisions will be recorded in [docs/ADR.md](docs/ADR.md).

## Planned features

- **Ledger:** double-entry accounts and transfers, with a no-overdraft limit on customer cash.
- **Retry-safe API:** collection and card-spend endpoints that honour the `Idempotency-Key` header.
- **Unit registry:** money market fund holdings with daily forward pricing; redemptions settle two business days later.
- **Sweep rule:** keeps each customer's cash near its target.
- **Reconciliation:** day-close checks of the ledger and the unit registry against the fund administrator, with open breaks exposed as metrics and alerts.
- **Observability:** Prometheus metrics, a Grafana dashboard and alert rules.

## Planned stack

Kotlin, Spring Boot 4, PostgreSQL and Flyway, JUnit 5 and Testcontainers, Micrometer, Prometheus and Grafana, Docker Compose, and a local Kubernetes cluster.

## Approach

I'm using this project to learn Kotlin and Spring Boot. Claude Code helps with scaffolding, configuration and review (see [CLAUDE.md](CLAUDE.md)); the domain logic is written by hand.

## Disclaimer

Everything is simulated: the fund, its prices and the administrator are fakes. No real money, and not affiliated with any financial institution.
