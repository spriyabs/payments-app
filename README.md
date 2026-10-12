# Payments App

A Spring Boot service that submits card payments to Meridian Pay using a token, tracks status from Meridian's webhooks, and lets the caller cancel a payment. Includes a minimal React page, Postgres, and a Meridian mock, all runnable with one command.

> **What works:** `POST /api/payments`, `GET /api/payments/{id}`, `POST /api/payments/{id}/cancellation`, `GET /api/currencies` and the signed `POST /webhooks/meridian` endpoint work against the Meridian mock. Payments move from `PDNG` to `ACCP`, `ACSC` or `RJCT`, and can be cancelled while `PDNG` or `ACCP`. The React page at http://localhost:3000 submits a payment, shows its status and lets you cancel. The spec is in [docs/spec.md](docs/spec.md) (section 14 is the build order) and the API contract in [docs/openapi.yaml](docs/openapi.yaml).

## Quickstart

Requirements: Docker with Docker Compose.

```bash
git clone https://github.com/spriyabs/payments-app.git
cd payments-app
cp .env.example .env      # optional: every value has a default in docker-compose.yml
docker compose up --build --wait
./scripts/smoke.sh        # checks that every service answers
```

| URL | What |
|-----|------|
| http://localhost:3000 | UI (React, served by nginx) |
| http://localhost:8080/actuator/health | Payments API health |
| http://localhost:9090/health | Meridian mock |
| localhost:5432 | Postgres (`payments` database) |

Stop with `docker compose down` (add `-v` to delete the database volume).

## Try the payment endpoints

Start Postgres and the mock, then run the API (in IntelliJ, or `cd payments-api && mvn spring-boot:run`):

```bash
docker compose up -d postgres meridian-mock
```

- **IntelliJ:** open `payments-api/http/payments.http`, pick the `local` environment and run the requests top to bottom. It generates a single-use token, submits a payment, repeats it (idempotency), and covers the error cases.
- **Terminal:** `./scripts/try-payment.sh` runs the same flow with curl. Set `OUTCOME=tok_decline_funds` to use a token the mock will decline. Tokenisation is out of scope, so any new `tok_...` value works; the prefix picks the outcome (see [docs/spec.md](docs/spec.md) section 10).

The mock sends a signed callback to the API a moment after each payment, so polling `GET /api/payments/{id}` shows `PDNG` then `ACCP`, and `ACSC` after `MOCK_SETTLE_AFTER_SECONDS`.

To cancel a payment, `POST /api/payments/{id}/cancellation` (no body). It returns `200` with status `CANC`, also when repeated, and `409 PAYMENT_NOT_CANCELLABLE` once the payment is `ACSC` or `RJCT`. The mock settles after 30 seconds by default (`MOCK_SETTLE_AFTER_SECONDS`), so cancel quickly or use `POST http://localhost:9090/mock/payments/<meridianPaymentId>/settle` to force settlement and see the 409.

To fire a webhook by hand (for example to test a decline or a bad status), use the `endToEndId` from the payment response:

```bash
./scripts/send-webhook.sh <endToEndId> RJCT AM04   # signed with the local-dev secret
./scripts/send-webhook.sh <endToEndId> ACCP
```

Set `API`, `SECRET` or `EVENT_ID` to override the target, the signing secret or the event id (reuse an `EVENT_ID` to see duplicates ignored).

## Try the page

Open http://localhost:3000 (after `docker compose up --build --wait`). A made-up token is filled in; enter an amount such as `125.50`, pick a currency and press Pay. The page follows the payment from Processing to Authorised, then Settled after about 30 seconds, and the Cancel button shows while it can still be cancelled.

- **Decline:** replace the token with `tok_decline_funds_1` (or `tok_decline_blocked_1`, `tok_decline_invalid_1`, `tok_decline_other_1`) to see a Declined payment with its reason.
- **Slow payment:** `tok_slow_1` stays Processing for 3 minutes.
- **Resume:** reload the page; the payment id is in the address (`?payment=...`) and tracking carries on.
- **Tokens are single-use:** use a new suffix for each payment (the page fills in a fresh token every time).

## Documentation

| File | Contents |
|------|----------|
| [docs/spec.md](docs/spec.md) | Design: architecture, state machine, behaviour rules, test plan, build order |
| [docs/openapi.yaml](docs/openapi.yaml) | Our API contract (source of truth) |

## Repository layout

```
docker-compose.yml        Postgres + mock + service + UI
.env.example              Local configuration (copy to .env)
docs/                     Spec and OpenAPI contract
payments-api/              Spring Boot 4.1, Java 21, Hibernate, Postgres
payment-ui/               React + webpack, Node is build-time only
meridian-mock/            Meridian mock: payments, cancel, signed callbacks, magic test tokens
scripts/                  smoke.sh (every service answers), try-payment.sh, send-webhook.sh
```

## Local development without Docker for the apps

```bash
docker compose up -d postgres meridian-mock
cd payments-api && mvn spring-boot:run     # http://localhost:8080
cd payment-ui && npm install && npm run dev   # http://localhost:3000
```

Use Node 22 (see `payment-ui/.nvmrc`) and Java 21.

## Tests

```bash
cd payments-api && mvn verify   # API unit and integration tests (needs Docker)
cd meridian-mock && npm test     # Meridian mock (Node only)
cd payment-ui && npm test        # UI polling, API and text rules (Node only)
```

Integration tests use **Testcontainers** with a real Postgres, so Docker must be running. Tests fail if Docker is missing; they are never silently skipped.

## Dependency versions

`pom.xml` uses Spring Boot 4.1.1 (open-source support until mid 2027) and overrides four versions it manages (Tomcat, Jackson, Logback, the Postgres driver) to pick up security fixes; each override says when it can be removed. `payment-ui/package-lock.json` pins the UI's npm dependencies, and the UI Dockerfile installs them with `npm ci`.

## Assumptions

The main assumptions, in short (full list in [docs/spec.md](docs/spec.md) sections 1, 15 and 16):

- **Tokenisation is out of scope.** The browser would get a single-use token from Meridian; here any new `tok_...` value is accepted by the mock, and its prefix picks the outcome.
- **Only 2-decimal currencies.** Meridian's amount format allows exactly two decimals, so JPY, KWD and similar are rejected with a clear `422`. No FX conversion.
- **Server-to-server auth** to Meridian is an `X-Api-Key` header, and webhook signatures are lowercase hex HMAC-SHA256 of the raw body (the provider spec names neither).
- **Settlement sends a webhook**, and late or duplicate callbacks can arrive at any time; they are stored, deduplicated by `eventId`, and never move a payment out of a final state.
- **No reconciliation job and no auth on our own API** in this version; a missed webhook is covered by Meridian's 24-hour retries (see spec section 16).
- **Hibernate creates the schema** (`ddl-auto: update`), with no migration tool.

## Configuration

All settings are environment variables, listed in [.env.example](.env.example). The UI reads `PAYMENTS_API_URL` when its container starts (nginx writes it to `/config.json` and into the Content-Security-Policy), so changing it only needs `docker compose up -d`, not a rebuild. `CORS_ALLOWED_ORIGIN` tells the API which browser origin may call it; keep it equal to the UI's address.

Using the real Meridian sandbox instead of the mock: set `MERIDIAN_BASE_URL`, `MERIDIAN_API_KEY`, `MERIDIAN_WEBHOOK_SECRET` and a public `CALLBACK_URL` (for example an ngrok URL pointing at `localhost:8080/webhooks/meridian`) in `.env`.

## Troubleshooting

- **Port already in use:** another process is using 3000, 5432, 8080 or 9090. Stop it or change the port mapping in `docker-compose.yml`.
- **`payments-api` unhealthy on first start:** it waits for Postgres, then takes up to about 40 seconds to boot. Check `docker compose logs payments-api`.
- **UI shows the wrong API URL:** check `PAYMENTS_API_URL` in `.env`, then restart the UI with `docker compose up -d payment-ui`. The URL must be reachable from your browser, not a compose-internal name.
- **Browser shows a network or CORS error:** `CORS_ALLOWED_ORIGIN` on the API must match the address you open the UI at (default `http://localhost:3000`), including the port.
- **Mock callbacks fail when the API runs in IntelliJ:** the mock runs in Docker and reaches your host through `host.docker.internal` (default `callback-url`). On Linux this relies on the `extra_hosts` entry already in `docker-compose.yml`. When the API itself runs in compose, it uses `http://payments-api:8080/webhooks/meridian` instead.
- **Tests fail to start:** make sure Docker is running (Testcontainers needs it).
