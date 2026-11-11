ALTER TABLE "EmergencyRequest"
  ALTER COLUMN "facilityId" DROP NOT NULL,
  ADD COLUMN "facilityName" TEXT NOT NULL DEFAULT '',
  ADD COLUMN "facilityLocation" TEXT NOT NULL DEFAULT '',
  ADD COLUMN "facilityLatitude" DOUBLE PRECISION,
  ADD COLUMN "facilityLongitude" DOUBLE PRECISION;

UPDATE "EmergencyRequest" AS request
SET
  "facilityName" = facility."name",
  "facilityLocation" = concat_ws(', ', facility."locality", facility."state")
FROM "Facility" AS facility
WHERE request."facilityId" = facility."id";

UPDATE "EmergencyRequest"
SET "status" = 'OPEN'
WHERE "status" IN ('PENDING_FACILITY_VERIFICATION', 'NEEDS_INFORMATION');
