# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Treasury Sweep is a learning project: a Kotlin and Spring Boot service with a double-entry ledger, a retry-safe API (`Idempotency-Key`), a money market fund unit registry, a cash sweep rule and day-close reconciliation against a simulated fund administrator, observed with Prometheus and Grafana. The design is in `docs/spec.md`; decisions go in `docs/ADR.md`.

`private/` is gitignored and exists only locally. It holds the build plan (`private/PLAN.md`: tiers, time boxes, cut order, checkpoint log) and the target (`private/TARGET.md`: why the project exists, and the honesty rules for describing it).

## Start of every session

1. If `private/` exists, read `private/PLAN.md` and `private/TARGET.md`.
2. Say which block we're in, roughly how much time is left in it, and when the next checkpoint is.
3. If a request falls outside the current tier, say so before starting it.

## Checkpoints

When a block's "done when" list is met, or at a checkpoint in `private/PLAN.md`, stop and walk through the checkpoint protocol there (target, goal, feasibility, honesty), then add a row to the checkpoint log. If we're behind, propose the next cut from the cut order rather than quietly extending the day.

## Who writes what

Leong is learning Kotlin and Spring Boot. He writes Go in production and knows Java from university, so explain Kotlin and Spring by comparison with Go where it helps: interfaces and dependency injection, error values vs exceptions and sealed results, goroutines vs threads, `context.Context` vs request scope and transactions, `defer` vs `use {}` and `try`/`finally`.

| Area | Who writes it | Claude Code's role                                                                                                                                                       |
|---|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Domain code: ledger, transfers, idempotency, unit registry, fund orders, sweep rule, reconciliation | Either | Generate on request; Hints, review, explaining errors, pointing to docs. Write domain code only when Leong asks for a specific piece; keep it small and walk through it. |
| Domain tests: what to test and the assertions | Either | Generate on request;Suggest missed cases, especially concurrency, rounding and failure paths.                                                                            |
| Build files, Flyway boilerplate, test fixtures | Either | Generate on request; explain anything non-obvious.                                                                                                                       |
| Docker Compose, Prometheus, Grafana, Kubernetes manifests, CI | Either | Generate on request, then walk Leong through each block: he has to be able to explain it in an interview.                                                                |

## Rules

- Never use floating point for money. Cash is `Long` cents in a value class; fund units (4 dp) and prices (6 dp) are `BigDecimal` with explicit rounding modes. See `docs/spec.md`.
- Domain code gets dates and times from the injected simulation clock, never from `LocalDate.now()`.
- Every transfer has one debit account, one credit account and one positive amount.
- Integration tests use PostgreSQL through Testcontainers, never H2.
- Keep the project generic: no real company names, logos, or claims about how a real provider works.
- Ask before adding a dependency, and say why it's needed.
- Take dependencies from start.spring.io rather than from tutorials. Many tutorials target Spring Boot 3; Spring Boot 4 changed some starter names and moved to Jackson 3.

## Commands

Docker must be running for anything that starts PostgreSQL (tests and `bootTestRun`). `./gradlew` works in PowerShell and Git Bash.

- `./gradlew build`: compile and run all tests; this is what CI runs (`.github/workflows/build.yml`).
- `./gradlew test`: tests only. One class: `./gradlew test --tests '*TreasurySweepApplicationTests'`.
- `./gradlew bootTestRun`: run the app against a throwaway Testcontainers PostgreSQL (`TestTreasurySweepApplication`). No `compose.yaml` needed.

Not yet available; update this list when they work:

- `docker compose up -d` then `./gradlew bootRun`: once `compose.yaml` exists (S1 or S3).
- `kubectl apply -f k8s/`: once the manifests exist (Saturday stretch or U4).
