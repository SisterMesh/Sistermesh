ALTER TYPE "EmergencyRequestStatus" ADD VALUE 'OPEN';
ALTER TYPE "EmergencyRequestStatus" ADD VALUE 'PARTIALLY_FILLED';
ALTER TYPE "EmergencyRequestStatus" ADD VALUE 'FULFILLED';

ALTER TYPE "DonorResponseStatus" ADD VALUE 'WITHDRAWN';

ALTER TYPE "RequestActivityType" ADD VALUE 'UPDATED';
ALTER TYPE "RequestActivityType" ADD VALUE 'DONOR_ACCEPTED';
ALTER TYPE "RequestActivityType" ADD VALUE 'DONOR_DECLINED';
ALTER TYPE "RequestActivityType" ADD VALUE 'DONOR_WITHDRAWN';
ALTER TYPE "RequestActivityType" ADD VALUE 'PARTIALLY_FILLED';
ALTER TYPE "RequestActivityType" ADD VALUE 'FULFILLED';

ALTER TABLE "DonorResponse"
ADD COLUMN "withdrawalIdempotencyKey" UUID,
ADD COLUMN "withdrawnAt" TIMESTAMP(3);

CREATE UNIQUE INDEX "DonorResponse_withdrawalIdempotencyKey_key"
ON "DonorResponse"("withdrawalIdempotencyKey");

UPDATE "EmergencyRequest" AS request
SET "status" = CASE
  WHEN request."status" = 'COMPLETED' THEN 'FULFILLED'::"EmergencyRequestStatus"
  ELSE CASE
    WHEN (
      SELECT COUNT(*) FROM "DonationConfirmation" AS confirmation
      WHERE confirmation."requestId" = request."id"
    ) >= request."units" THEN 'FULFILLED'::"EmergencyRequestStatus"
    WHEN (
      SELECT COUNT(*) FROM "DonationConfirmation" AS confirmation
      WHERE confirmation."requestId" = request."id"
    ) > 0 THEN 'PARTIALLY_FILLED'::"EmergencyRequestStatus"
    ELSE 'OPEN'::"EmergencyRequestStatus"
  END
END
WHERE request."status" IN ('SEARCHING', 'WAITING', 'ACCEPTED', 'COMPLETED');
