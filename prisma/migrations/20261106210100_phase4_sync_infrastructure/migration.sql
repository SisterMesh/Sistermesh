ALTER TABLE "SyncBatch"
ADD COLUMN "requestHash" TEXT;

CREATE TABLE "SyncCursorState" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "value" BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT "SyncCursorState_pkey" PRIMARY KEY ("id")
);

INSERT INTO "SyncCursorState" ("id", "value") VALUES (1, 0);

ALTER TABLE "RequestActivity"
ADD COLUMN "syncCursor" BIGINT,
ADD COLUMN "syncEventId" UUID;

WITH ordered_activity AS (
    SELECT "id", ROW_NUMBER() OVER (ORDER BY "occurredAt", "id") AS cursor
    FROM "RequestActivity"
)
UPDATE "RequestActivity" AS activity
SET "syncCursor" = ordered_activity.cursor
FROM ordered_activity
WHERE activity."id" = ordered_activity."id";

UPDATE "SyncCursorState"
SET "value" = COALESCE((SELECT MAX("syncCursor") FROM "RequestActivity"), 0)
WHERE "id" = 1;

ALTER TABLE "RequestActivity"
ALTER COLUMN "syncCursor" SET NOT NULL;

CREATE UNIQUE INDEX "RequestActivity_syncCursor_key" ON "RequestActivity"("syncCursor");
CREATE UNIQUE INDEX "RequestActivity_syncEventId_key" ON "RequestActivity"("syncEventId");

CREATE TABLE "SyncEvent" (
    "id" UUID NOT NULL,
    "eventId" UUID,
    "registeredDeviceId" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "batchId" UUID NOT NULL,
    "eventType" TEXT,
    "entityId" TEXT,
    "originDeviceId" TEXT,
    "localCreatedAt" TIMESTAMP(3),
    "payloadSchemaVersion" INTEGER,
    "correlationId" TEXT,
    "payload" JSONB NOT NULL,
    "payloadHash" TEXT NOT NULL,
    "status" "SyncEventStatus" NOT NULL DEFAULT 'RECEIVED',
    "errorCode" TEXT,
    "explanation" TEXT,
    "resultEntityId" UUID,
    "attemptCount" INTEGER NOT NULL DEFAULT 1,
    "leaseUntil" TIMESTAMP(3),
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "processedAt" TIMESTAMP(3),
    CONSTRAINT "SyncEvent_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "SyncConflict" (
    "id" UUID NOT NULL,
    "syncEventId" UUID,
    "eventId" UUID NOT NULL,
    "registeredDeviceId" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "payloadHash" TEXT NOT NULL,
    "conflictCode" TEXT NOT NULL,
    "explanation" TEXT NOT NULL,
    "incomingPayload" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SyncConflict_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "SyncCheckpoint" (
    "id" UUID NOT NULL,
    "registeredDeviceId" UUID NOT NULL,
    "lastCursor" BIGINT NOT NULL DEFAULT 0,
    "pendingCursor" BIGINT,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "SyncCheckpoint_pkey" PRIMARY KEY ("id")
);

INSERT INTO "SyncCheckpoint" ("id", "registeredDeviceId", "lastCursor", "updatedAt")
SELECT gen_random_uuid(), "id", 0, CURRENT_TIMESTAMP
FROM "RegisteredDevice";

CREATE UNIQUE INDEX "SyncEvent_eventId_key" ON "SyncEvent"("eventId");
CREATE INDEX "SyncEvent_userId_receivedAt_idx" ON "SyncEvent"("userId", "receivedAt");
CREATE INDEX "SyncEvent_registeredDeviceId_receivedAt_idx" ON "SyncEvent"("registeredDeviceId", "receivedAt");
CREATE INDEX "SyncEvent_batchId_receivedAt_idx" ON "SyncEvent"("batchId", "receivedAt");
CREATE INDEX "SyncEvent_status_leaseUntil_idx" ON "SyncEvent"("status", "leaseUntil");
CREATE UNIQUE INDEX "SyncConflict_eventId_payloadHash_registeredDeviceId_key" ON "SyncConflict"("eventId", "payloadHash", "registeredDeviceId");
CREATE INDEX "SyncConflict_userId_createdAt_idx" ON "SyncConflict"("userId", "createdAt");
CREATE INDEX "SyncConflict_registeredDeviceId_createdAt_idx" ON "SyncConflict"("registeredDeviceId", "createdAt");
CREATE UNIQUE INDEX "SyncCheckpoint_registeredDeviceId_key" ON "SyncCheckpoint"("registeredDeviceId");

ALTER TABLE "SyncEvent"
ADD CONSTRAINT "SyncEvent_registeredDeviceId_fkey"
FOREIGN KEY ("registeredDeviceId") REFERENCES "RegisteredDevice"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncEvent"
ADD CONSTRAINT "SyncEvent_userId_fkey"
FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncEvent"
ADD CONSTRAINT "SyncEvent_batchId_fkey"
FOREIGN KEY ("batchId") REFERENCES "SyncBatch"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncConflict"
ADD CONSTRAINT "SyncConflict_syncEventId_fkey"
FOREIGN KEY ("syncEventId") REFERENCES "SyncEvent"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncConflict"
ADD CONSTRAINT "SyncConflict_registeredDeviceId_fkey"
FOREIGN KEY ("registeredDeviceId") REFERENCES "RegisteredDevice"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncConflict"
ADD CONSTRAINT "SyncConflict_userId_fkey"
FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "SyncCheckpoint"
ADD CONSTRAINT "SyncCheckpoint_registeredDeviceId_fkey"
FOREIGN KEY ("registeredDeviceId") REFERENCES "RegisteredDevice"("id") ON DELETE CASCADE ON UPDATE CASCADE;
