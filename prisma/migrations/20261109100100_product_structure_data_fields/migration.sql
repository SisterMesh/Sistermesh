UPDATE "User"
SET "role" = 'INDIVIDUAL'::"UserRole"
WHERE "role" = 'DONOR'::"UserRole";

ALTER TABLE "EmergencyRequest"
ADD COLUMN "isForSelf" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN "beneficiaryName" TEXT,
ADD COLUMN "beneficiaryRelationship" TEXT,
ADD COLUMN "additionalInfo" TEXT,
ADD COLUMN "reviewReason" TEXT,
ADD COLUMN "reviewedByUserId" UUID,
ADD COLUMN "reviewedAt" TIMESTAMP(3);

ALTER TABLE "EmergencyRequest"
ADD CONSTRAINT "EmergencyRequest_reviewedByUserId_fkey"
FOREIGN KEY ("reviewedByUserId") REFERENCES "User"("id")
ON DELETE SET NULL ON UPDATE CASCADE;

CREATE INDEX "EmergencyRequest_createdByUserId_status_createdAt_idx"
ON "EmergencyRequest"("createdByUserId", "status", "createdAt");
