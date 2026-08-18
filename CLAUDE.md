# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Always use the sdkman-managed JDK 21 — the Gradle daemon on this machine defaults to a
# newer/unrelated JDK that breaks the Kotlin daemon. Set this in every shell before ./gradlew:
export JAVA_HOME=~/.sdkman/candidates/java/21.0.5-tem
export PATH=$JAVA_HOME/bin:$PATH

./gradlew build              # compile + tests
./gradlew run                # run locally, needs .env (copy from .env.example first)
./gradlew test                # tests only (JUnit via kotlin.test)
./gradlew test --tests "com.arabaskor360.SomeTest"   # single test class

./scripts/setup-platform.sh  # one-time: registers the "araba-skor" platform + role + free
                              # plan on user-platform-service (needs ADMIN_SECRET in .env)

docker build -t araba-skor-360-server .
docker run -p 8080:8080 -e PORT=8080 -e DATABASE_URL=... -e USER_PLATFORM_SERVICE_URL=... araba-skor-360-server
```

There is no linter/formatter configured. No test suite exists yet (`src/test` is empty).

## Architecture

Kotlin + Javalin 6.7.0 backend for a car-scoring app. Serves pre-computed vehicle scores and lets
users submit their own 1-100 score + free-text comment per car.

**Request flow**: `Application.kt` wires routes directly to `*Controller` classes (no DI
framework). Controllers parse/validate input and delegate to `*Repository` classes, which run
Exposed DSL queries inside `transaction { }` blocks. There's no service layer — controllers talk
to repositories directly.

**Two categories of DB tables**, both in the same Postgres database (`carscore`), owned differently:
- `model_variant` / `model_variant_score` ([db/tables](src/main/kotlin/com/arabaskor360/db/tables)) —
  pre-existing tables owned by an external data pipeline (not in this repo). This app only reads
  them via read-only Exposed `Table` objects; never migrates or writes to them. The score
  calculation logic lives in that external pipeline, not here — see the "Skorlama" section in
  [README.md](README.md) for what's actually knowable about it from this codebase.
- `user_car_review` — owned by this app, created via the single Flyway migration in
  `src/main/resources/db/migration/`. Holds one row per (user, car) — upserted, not
  append-only — with a denormalized `displayName`/`avatarUrl` snapshot taken at write time so
  listing reviews doesn't require calling out to user-platform-service.

**Auth is fully delegated** to a separate service, `user-platform-service` (sibling project, not
in this repo). This app never verifies Firebase tokens itself — `platform/AuthMiddleware.kt`'s
`Context.requireUserContext()` forwards the raw `Authorization` header to
`GET /v1/users/me/context/araba-skor` on that service and trusts its response. Two distinct
failure policies, both intentional:
- **Fail-closed** for identity: if user-platform-service is unreachable or rejects the token,
  the request is rejected (401/503) — see `UserPlatformClient.fetchUserContext`.
- **Fail-open** for quota: if the `daily_reviews` usage-consume call fails for any reason other
  than an explicit 429, the review is still accepted (`UserPlatformClient.tryConsumeUsage`
  returns null and the caller proceeds) — quota is a soft limit, not a security boundary.

**Community score** (`CarRepository.communityStatsFor`) is a plain `AVG(score)` /
`COUNT(*)` GROUP BY query over `user_car_review`, computed at read time, not cached or
materialized.

## Non-obvious version constraints (don't casually bump these)

- **Javalin is pinned to 6.7.0, not 7.x.** Javalin 7 moved routing (`get`/`post`/`before`/
  `exception`) off the `Javalin` instance entirely onto a separate `config.routes` object, and
  restructured the OpenAPI plugin ecosystem around it. 6.7.0 is the last version with the
  classic API this codebase uses.
- **Kotlin is pinned to 2.4.10.** Exposed 1.4.0's jars carry a newer Kotlin metadata binary
  version than the Kotlin compiler Gradle 8.13 bundles by default (2.0.21) can read —
  compilation fails with an "incompatible version of Kotlin" error otherwise.
- **Exposed 1.x lives under `org.jetbrains.exposed.v1.*`** (`v1.core`, `v1.jdbc`,
  `v1.javatime`), not the `org.jetbrains.exposed.sql.*` namespace most Exposed docs/examples
  online still show — this is a recent package rename.
- **`javalin-swagger-plugin` is pinned to `6.7.0-5`** — this plugin's versions track Javalin
  core releases 1:1 (`<javalin-version>-<patch>`), not independent semver.
- **Flyway needs `baselineOnMigrate(true)` + `baselineVersion("0")`**
  ([db/Database.kt](src/main/kotlin/com/arabaskor360/db/Database.kt)). The `public` schema
  already has tables this app doesn't own (`model_variant`, ...), so Flyway refuses to migrate
  an "already non-empty" schema unless baselined — and the baseline version must sit below `V1`
  or Flyway treats `V1` as already-applied and silently skips actually running it.

## Swagger / OpenAPI

`src/main/resources/openapi.yaml` is hand-written, not generated — keep it in sync manually when
routes change. `javalin-swagger-plugin`'s `documentationPath` config only auto-populates when
paired with the (unused here) annotation-based `OpenApiPlugin` code generator; for a static
hand-written spec, `Application.kt` wires it via `swagger.injectCustomVersion("v1",
OPENAPI_PATH)` instead — using `documentationPath` alone silently renders an empty spec list.

## Deployment

Deploys to Railway via `Dockerfile` (multi-stage: `eclipse-temurin:21-jdk` build → `21-jre`
runtime), forced by `railway.toml` (`builder = "DOCKERFILE"`) to stop Railway from guessing via
Nixpacks. `AppConfig.port` reads the `PORT` env var Railway injects at runtime; never hardcode it.

Must run in the same Railway project as `user-platform-service` for private networking —
`USER_PLATFORM_SERVICE_URL` should point at `http://<service>.railway.internal:<port>`, not the
public domain, when both are co-located. `DATABASE_URL` stays a public proxy URL
(`yamabiko.proxy.rlwy.net:...`) since the `carscore` Postgres lives in a different Railway
project than this app.
