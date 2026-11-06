ALTER TABLE "DonorResponse"
ADD COLUMN "withdrawalIdempotencyKey" UUID,
ADD COLUMN "withdrawnAt" TIMESTAMP(3),
ADD COLUMN "submittedStatus" "DonorResponseStatus";

UPDATE "DonorResponse"
SET "submittedStatus" = CASE
  WHEN "status" = 'CONFIRMED' THEN 'ACCEPTED'::"DonorResponseStatus"
  ELSE "status"
END;

CREATE UNIQUE INDEX "DonorResponse_withdrawalIdempotencyKey_key"
ON "DonorResponse"("withdrawalIdempotencyKey");

UPDATE "EmergencyRequest" AS request
SET "status" = CASE
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
WHERE request."status" IN ('SEARCHING', 'WAITING', 'ACCEPTED', 'COMPLETED');
