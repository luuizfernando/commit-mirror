# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot service that mirrors work commits to a public GitHub log so they count on the contribution graph. Flow: `/commit <msg>` in a Telegram bot → webhook → API records it in Postgres and appends a line to `logs/AAAA-MM.md` in this repo via the GitHub Contents API (explicit author/committer/dates). The typed message text is never written to the repo or logs. Log line and commit message are just `Commit N` (global, never-resetting counter, column `sequence_number` with `UNIQUE`).

## Commands

Requires **JDK 21** on `JAVA_HOME` (the machine default may be Java 8). Use the wrapper:

```
./mvnw verify                              # unit tests (surefire, *Test) + integration tests (failsafe, *IT) + JaCoCo check
./mvnw test                                # unit tests only
./mvnw test -Dtest=ClassName#method        # single unit test
./mvnw verify -Dit.test=HealthEndpointIT   # single integration test (failsafe)
./mvnw dependency-check:check              # OWASP scan, local only (removed from CI, NVD API too slow); fails at CVSS >= 7.0; set NVD_API_KEY
```

Integration tests use Testcontainers (real Postgres), so Docker must be running. Shell scripts in `deploy/` are linted with shellcheck and workflows with actionlint in CI.

## Conventions and constraints

- Spring Boot **4.1** (Spring Framework 7, Jackson 3 under `tools.jackson`, JUnit 6, modular starters). Use `@MockitoBean`, not `@MockBean`.
- No Lombok; DTOs are records. External HTTP uses Spring `RestClient` (no Telegram library).
- Resilience4j is planned via core modules configured programmatically, not the Boot 3 starter.
- `build-info` is enabled and `finalName` is `app`; version comes from `${revision}`.
- JaCoCo `verify` gates: ≥80% line coverage on `service` and `domain` packages, ≥70% on the bundle. `config`, `dto`, `exception` and the application class are excluded.
- Schema is managed by Flyway; `ddl-auto: validate`, `open-in-view: false`.
- Actuator runs on a separate management port (`MANAGEMENT_PORT`, default 8081), exposing only `health` and `info` with probes. `HealthEndpointIT` asserts this.
- Config comes from env vars (`DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `SERVER_PORT`, `MANAGEMENT_PORT`); see `.env.example`.

## CI/CD and deploy

`.github/workflows/pipeline.yml`: lint → `./mvnw verify` → compute version → build the Docker image once, smoke-test it (`deploy/smoke-test.sh`), push that exact image to Docker Hub → tag/release → deploy over SSH (`deploy/deploy.sh`), flagging the release on failure. The VPS does not clone the repo. It holds only `docker-compose.yaml`, `.env` and `deploy.sh` (in `/projects/commit-mirror`, run as root), and the pipeline only swaps the image tag. All secrets are repository-level.
