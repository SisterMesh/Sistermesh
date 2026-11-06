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

The Prisma schema is in `prisma/schema.prisma`. After reviewing the generated
initial migration and confirming the target schema is safe to extend, apply it
with:

```sh
npm run prisma:migrate:deploy
```

Migrations are additive and should never be used to replace the existing Neon
database. Do not run destructive schema changes without explicit approval.

## Checks

```sh
npm test
npm run test:e2e
npm run test:db
npm run build
```

The database connectivity test requires `DATABASE_URL`.
