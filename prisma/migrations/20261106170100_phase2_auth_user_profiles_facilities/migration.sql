DO $$
BEGIN
  CREATE TYPE "HealthWorkerVerificationStatus" AS ENUM ('PENDING', 'VERIFIED', 'REJECTED');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END
$$;

DO $$
BEGIN
  CREATE TYPE "FacilityStatus" AS ENUM ('PENDING', 'ACTIVE', 'INACTIVE', 'REJECTED');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END
$$;

ALTER TABLE "User"
ADD COLUMN IF NOT EXISTS "passwordHash" TEXT;

ALTER TABLE "DonorProfile"
ADD COLUMN IF NOT EXISTS "isProfileComplete" BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE "HealthWorkerProfile"
ADD COLUMN IF NOT EXISTS "professionalIdentifier" TEXT,
ADD COLUMN IF NOT EXISTS "verificationStatus" "HealthWorkerVerificationStatus" NOT NULL DEFAULT 'PENDING',
ADD COLUMN IF NOT EXISTS "verifiedAt" TIMESTAMP(3);

ALTER TABLE "Facility"
ADD COLUMN IF NOT EXISTS "facilityType" TEXT,
ADD COLUMN IF NOT EXISTS "address" TEXT,
ADD COLUMN IF NOT EXISTS "localGovernmentArea" TEXT,
ADD COLUMN IF NOT EXISTS "status" "FacilityStatus" NOT NULL DEFAULT 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS "HealthWorkerProfile_professionalIdentifier_key"
ON "HealthWorkerProfile"("professionalIdentifier");

CREATE INDEX IF NOT EXISTS "HealthWorkerProfile_facilityId_verificationStatus_idx"
ON "HealthWorkerProfile"("facilityId", "verificationStatus");
