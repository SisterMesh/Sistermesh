-- CreateEnum
CREATE TYPE "HealthWorkerVerificationStatus" AS ENUM ('PENDING', 'VERIFIED', 'REJECTED');

-- CreateEnum
CREATE TYPE "FacilityStatus" AS ENUM ('PENDING', 'ACTIVE', 'INACTIVE', 'REJECTED');

-- AlterTable
ALTER TABLE "User" ADD COLUMN     "passwordHash" TEXT;

-- AlterTable
ALTER TABLE "DonorProfile" ADD COLUMN     "isProfileComplete" BOOLEAN NOT NULL DEFAULT false,
ALTER COLUMN "bloodGroup" DROP NOT NULL;

-- AlterTable
ALTER TABLE "HealthWorkerProfile" ADD COLUMN     "professionalIdentifier" TEXT,
ADD COLUMN     "verificationStatus" "HealthWorkerVerificationStatus" NOT NULL DEFAULT 'PENDING';

-- AlterTable
ALTER TABLE "Facility" ADD COLUMN     "address" TEXT,
ADD COLUMN     "facilityType" TEXT,
ADD COLUMN     "localGovernmentArea" TEXT,
ADD COLUMN     "status" "FacilityStatus" NOT NULL DEFAULT 'PENDING';

-- CreateIndex
CREATE UNIQUE INDEX "HealthWorkerProfile_professionalIdentifier_key" ON "HealthWorkerProfile"("professionalIdentifier");

-- CreateIndex
CREATE INDEX "HealthWorkerProfile_facilityId_verificationStatus_idx" ON "HealthWorkerProfile"("facilityId", "verificationStatus");

-- CreateIndex
CREATE INDEX "Facility_state_localGovernmentArea_idx" ON "Facility"("state", "localGovernmentArea");

-- CreateIndex
CREATE INDEX "Facility_status_state_idx" ON "Facility"("status", "state");

