# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot service that mirrors work commits to a public GitHub log so they count on the contribution graph. Flow: `/commit <msg>` in a Telegram bot → webhook → API records it in Postgres and appends a line to `commit-logs/AAAA-MM.md` in this repo via the GitHub Contents API (explicit author/committer/dates). The typed message text is never written to the repo or logs. Log line and commit message are just `Commit N` (global, never-resetting counter, column `sequence_number` with `UNIQUE`). `/status` replies with how many commits were mirrored: `COUNT` of `commit_log` rows with `status = SUCCESS` (pending/failed rows don't count).

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
- `build-info` is enabled and `finalName` is `app`; version comes from `${revision}`.
- JaCoCo `verify` gates: ≥80% line coverage on `service` and `domain` packages, ≥70% on the bundle. `config`, `dto`, `exception` and the application class are excluded.
- Schema is managed by Flyway; `ddl-auto: validate`, `open-in-view: false`.
- Actuator runs on a separate management port (`MANAGEMENT_PORT`, default 8081), exposing only `health` and `info` with probes. `HealthEndpointIT` asserts this.
- Config comes from env vars (`DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `SERVER_PORT`, `MANAGEMENT_PORT`, `TELEGRAM_*`, `APP_GITHUB_PAT`, `GITHUB_*`, `GIT_AUTHOR_*`, `RECOVERY_*`); see `.env.example` and `docs/RUNBOOK.md`. The app refuses to start without the Telegram and GitHub secrets.
- Bot commands are `BotCommand` beans in `service/command` (`CommitCommand`, `StatusCommand`), collected by `CommandRegistry` and routed by `UpdateDispatcher` (which already enforces the allowlist and replies to unknown commands). A new command is just a new `@Component`.
- Resilience4j runs as core modules configured in `config/ResilienceConfig`; GitHub writes are serialized through `LogWriteSerializer` (single worker) and unfinished commits are re-queued by `PendingRecoveryService`.
- Integration tests extend `AbstractFlowIT` (real Postgres + stateful `FakeGitHubServer`/`FakeTelegramServer` in `src/test/.../support`, also runnable standalone for manual tests).

## Comments

Avoid comments as much as possible, in production and test code alike (no Javadoc on classes, methods or fields, no section markers in test classes). A comment that explains code means the code must be refactored: extract a method, introduce a variable or constant, or rename until the code says it on its own. A method name must state exactly what the method does, so a comment explaining a method is useless.

## Testing

### Non-negotiable rule

Never alter a test just to make it pass. If a test (new or existing) fails, the problem is in the production code or in the understanding of the requirement, never in the test's expectation. Fix the production code, or stop and talk to the user if the failure reveals an ambiguity in the requirement. Changing an assertion, expected value, mock, setup, timeout or adding `.skip`/`@Disabled` to make a test pass is forbidden, without exception.

### Rules

1. **Quantity**: no minimum or maximum. Write as many tests as needed to truly cover the functionality, neither more nor less to meet a numerical target (the JaCoCo gates are a floor, not a goal).
2. **One concept per test**: each test validates a single behavior. If a test verifies more than one, split it.
3. **Clean test code**: same quality standard as production code. Short test methods and descriptive names for tests and variables; the name must make clear what is validated and under which conditions without reading the body.
4. **F.I.R.S.T.**
   - **Fast**: avoid real I/O, real network calls and unnecessary sleeps/timeouts in unit tests; use mocks/stubs where it makes sense. Real Postgres and fake HTTP servers belong only in `*IT` tests.
   - **Independent**: each test runs in isolation and in any order; no test depends on state left by another (reset DB rows and fake server state between tests).
   - **Repeatable**: same result in any environment (dev, CI, any machine). No reliance on system date/time, variable external data or execution order; inject a fixed `Clock` (see `ClockConfig`) instead of `Clock.systemUTC()`.
   - **Self-validating**: a clear pass/fail via assertions, never manual inspection of logs or output.
   - **Timely**: write tests alongside (or immediately before) the production code they cover, not as a disconnected batch after the feature is done.
5. **Trivial tests count too**: don't skip "obvious" cases, such as the correct return value for the simplest happy path.
6. **Always consider edge cases**: null/empty values, empty lists, numeric limits (zero, negative, maximum), expected errors/exceptions, timeouts, and concurrency where applicable (e.g. `LogWriteSerializer`, the `sequence_number` `UNIQUE` constraint).

### Reporting

When presenting created tests, group them by what they cover (e.g. "happy path", "input validation", "edge cases", "error handling") and state, one line per test, what it validates, not just the method name. If project conventions are missing for something, ask before writing the tests; never make silent assumptions in the final response.

## CI/CD and deploy

`.github/workflows/pipeline.yml`: lint → `./mvnw verify` → compute version → build the Docker image once, smoke-test it (`deploy/smoke-test.sh`), push that exact image to Docker Hub → tag/release → deploy over SSH (`deploy/deploy.sh`), flagging the release on failure. The VPS does not clone the repo. It holds only `docker-compose.yaml`, `.env` and `deploy.sh` (in `/projects/commit-mirror`, run as root), and the pipeline only swaps the image tag. All secrets are repository-level.
