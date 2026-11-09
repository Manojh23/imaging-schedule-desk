# Imaging Schedule Desk

A synthetic outpatient imaging scheduling work sample. A coordinator can view a daily scanner worklist and book, move, or cancel fictional appointments. The UI uses UTC and accepts only `DEMO-` references; there are no names, patient records, or clinical data. This is a local portfolio app, not a production imaging system.

## Run locally

With Docker and Docker Compose:

```bash
export DEMO_DB_PASSWORD="$(openssl rand -hex 18)"
docker compose up --build
```

Open http://127.0.0.1:8080. Book a scanner at 09:00 for 30 minutes. Start a second booking for the same scanner at 09:15 and see the conflict; try 09:30 to book the adjacent slot. Click the original reference to move or cancel it and inspect its history.

For development, use JDK 21, Maven, Node 22, and PostgreSQL 16. Create a disposable PostgreSQL database named `imaging`. Export `DATABASE_URL` (a JDBC URL), `DATABASE_USER`, and `DATABASE_PASSWORD`. Run `mvn spring-boot:run` from the repo root and `npm ci && npm run dev` from `web/`. Vite proxies the API to localhost:8080. Flyway creates the tables and demo scanners.

## Scheduling invariants

- **Overlap:** A Spring transaction locks the target scanner row before checking active time ranges. Overlap uses half-open intervals, so a 09:30 slot can follow one ending at 09:30. Every booking or move uses the same lock protocol. Direct database writes would need to preserve that protocol.
- **Retries:** A request UUID and a normalized payload hash distinguish a safe retry from a conflicting key reuse. A transaction-scoped advisory lock serializes requests with the same key.
- **Concurrent edits:** Updates require the last seen appointment version. Stale requests return HTTP 409 and the client reloads the worklist.
- **Traceability:** Booking, move, and cancellation history is committed in the same transaction as the corresponding appointment change. The service does not require clinical fields.
- **Operations:** Actuator exposes database readiness and application liveness. Docker packages the UI and API together. `deploy/workload.yaml` is an example Kubernetes workload with probes and database configuration from a Secret. It requires a built image and a PostgreSQL service; it is not a deployed GKE environment.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /api/scanners | Load demo scanners |
| GET | /api/appointments?date=YYYY-MM-DD | Read a UTC daily worklist |
| POST | /api/appointments | Create or safely replay a booking |
| PATCH | /api/appointments/{id} | Move an appointment by version |
| POST | /api/appointments/{id}/cancel | Cancel by version |
| GET | /api/appointments/{id}/events | Read change history |

## Verification

Use a **separate disposable** database named `imaging_test`. Point the same database environment variables at it, set `SCHEDULE_TEST_DB=1`, and run `mvn test`. The integration tests start the HTTP server and use PostgreSQL, including simultaneous overlapping requests, safe replay, stale edits, cancellation history, validation, and database readiness. Run `npm run build` in `web/` for TypeScript and bundling checks. GitHub Actions runs both commands.

Codex assisted with implementation. Verification focused on the lock scope and transaction boundary, the HTTP results for retry and conflict cases, and whether the TypeScript client sends the expected version. This demo is intended to stay local; it has no user authentication, patient integrations, or clinical use.
