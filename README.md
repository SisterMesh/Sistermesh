# SisterMesh Backend

NestJS and Prisma backend for the SisterMesh Android application. It provides
password-based authentication, role-scoped user and facility management,
emergency coordination APIs, and Swagger documentation.

The Phase 4 offline synchronization contract is documented in
[docs/offline-sync-contract.md](docs/offline-sync-contract.md).

## Configuration

Set `DATABASE_URL` in Replit Secrets to the existing Neon PostgreSQL database.
Do not put the real connection string in source control. `PORT` is optional and
defaults to `5000`.

## Run

```sh
npm run prisma:generate
npm run start:dev
```

The API is served under `/api`; Swagger UI is at `/api/docs`, and the public
health check is `GET /api/health`.

## Authentication and profiles

- Donors register at `POST /api/auth/register/donor`; health workers register
  at `POST /api/auth/register/health-worker`.
- `POST /api/auth/login` returns a short-lived access token. A rotating refresh
  token is stored in an HttpOnly, Secure cookie and is never returned in JSON.
- `POST /api/auth/refresh` rotates that cookie; `POST /api/auth/logout` revokes
  the presented refresh session.
- Send access tokens as `Authorization: Bearer <token>`. `GET /api/auth/me` and
  `GET/PATCH /api/users/me` operate on the authenticated account only.
- Donor profiles use `GET/POST/PATCH /api/donors/me`; health worker profiles use
  `GET/POST/PATCH /api/health-workers/me`.
- Health workers register as unverified and are not assigned a facility until
  an administrator verifies and associates them.

## Facilities and authorization

- `GET /api/facilities` and `GET /api/facilities/:facilityId` expose public
  details for active facilities.
- Administrators manage facilities under `/api/admin/facilities` and verify or
  associate workers under `/api/admin/health-workers`.
- New facilities require administrator approval. Only verified health workers
  assigned to active facilities can use facility-scoped coordination routes.
- Registration never accepts a role field and there is no public admin
  registration route. Initial administrator provisioning is an operator task.

## Coordination endpoints

- Health workers create and manage facility requests under
  `/api/emergency-requests`, including status, cancellation, and activity
  history.
- Donors view compatible active requests at `GET
  /api/emergency-requests/eligible`, submit one response per request at
  `POST /api/donor-responses/:requestId`, and list responses at
  `GET /api/donor-responses/mine`.
- Health workers confirm a donation at
  `POST /api/emergency-requests/:requestId/donations/confirm`.
- Request creation uses a UUID `clientRequestId`; donor responses and donation
  confirmations use UUID `idempotencyKey` values.

## Database

The Prisma schema is in `prisma/schema.prisma`; migration files are in
`prisma/migrations`. After reviewing each new additive migration and confirming
the target schema is safe to extend, apply it with:

```sh
npm run prisma:migrate:deploy
```

Migrations are additive and should never be used to replace the existing Neon
database. Do not run destructive schema changes without explicit approval.

## Authentication and administrator access

Set the existing `SESSION_SECRET` in Replit Secrets to a random value of at
least 32 bytes. The API derives separate access-token and refresh-token signing
keys from it. Access tokens last 15 minutes; refresh tokens are rotated, stored
only as hashes, and sent only in an HTTP-only cookie.

Public registration accepts only `DONOR` or `HEALTH_WORKER`; it cannot create
administrators. To bootstrap the first administrator, first register an active
donor or health-worker account and verify that person through your controlled
process, then run `npm run admin:promote` from an authorised Replit shell and
confirm the exact account. Later role changes
are restricted to authenticated administrator endpoints. Never run the
bootstrap command in an untrusted shell.

Facilities are created by administrators in `PENDING` status and require an
administrator status change before they become available. Health workers begin
unverified; only administrators can verify them or assign them to an active
facility.

## Checks

```sh
npm test
npm run test:e2e
npm run test:db
npm run build
```

The database connectivity and migration checks require `DATABASE_URL`.
