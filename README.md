# SisterMesh Backend

NestJS and Prisma backend foundation for the SisterMesh Android application.
The first phase contains the API health check, Swagger documentation, and
initial relational schema only. Authentication and donor-coordination business
endpoints are not implemented yet.

## Configuration

Set `DATABASE_URL` in Replit Secrets to the existing Neon PostgreSQL database.
Do not put the real connection string in source control. `PORT` is optional and
defaults to `5000`.

## Run

```sh
npm run prisma:generate
npm run start:dev
```

The API is served under `/api`; Swagger UI is at `/api/docs`, and the health
check is `GET /api/health`.

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

The database connectivity test requires `DATABASE_URL`.
