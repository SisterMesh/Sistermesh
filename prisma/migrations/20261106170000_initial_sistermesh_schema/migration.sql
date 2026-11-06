-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "UserRole" AS ENUM ('DONOR', 'HEALTH_WORKER', 'ADMIN');

-- CreateEnum
CREATE TYPE "AccountStatus" AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED');

-- CreateEnum
CREATE TYPE "BloodGroup" AS ENUM ('A_POSITIVE', 'A_NEGATIVE', 'B_POSITIVE', 'B_NEGATIVE', 'AB_POSITIVE', 'AB_NEGATIVE', 'O_POSITIVE', 'O_NEGATIVE');

-- CreateEnum
CREATE TYPE "RequestUrgency" AS ENUM ('URGENT', 'CRITICAL');

-- CreateEnum
CREATE TYPE "EmergencyRequestStatus" AS ENUM ('SEARCHING', 'WAITING', 'ACCEPTED', 'COMPLETED', 'CANCELLED', 'EXPIRED');

-- CreateEnum
CREATE TYPE "DonorResponseStatus" AS ENUM ('PENDING', 'DELIVERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CONFIRMED');

-- CreateEnum
CREATE TYPE "RequestActivityType" AS ENUM ('CREATED', 'DISTRIBUTED', 'DONOR_RESPONDED', 'STATUS_CHANGED', 'CANCELLED', 'EXPIRED', 'DONATION_CONFIRMED');

-- CreateEnum
CREATE TYPE "NotificationStatus" AS ENUM ('QUEUED', 'SENT', 'FAILED', 'READ');

-- CreateEnum
CREATE TYPE "SyncBatchStatus" AS ENUM ('RECEIVED', 'PROCESSING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED');

-- CreateEnum
CREATE TYPE "MeshMessageType" AS ENUM ('EMERGENCY_REQUEST', 'DONOR_RESPONSE', 'REQUEST_STATUS');

-- CreateTable
CREATE TABLE "User" (
    "id" UUID NOT NULL,
    "role" "UserRole" NOT NULL,
    "status" "AccountStatus" NOT NULL DEFAULT 'ACTIVE',
    "displayName" TEXT NOT NULL,
    "phoneNumber" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DonorProfile" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "bloodGroup" "BloodGroup" NOT NULL,
    "isAvailable" BOOLEAN NOT NULL DEFAULT true,
    "locality" TEXT,
    "lastActiveAt" TIMESTAMP(3),
    "consentedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DonorProfile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "HealthWorkerProfile" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "facilityId" UUID,
    "designation" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "HealthWorkerProfile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Facility" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "locality" TEXT,
    "state" TEXT,
    "contactPhone" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Facility_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RegisteredDevice" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "clientDeviceId" TEXT NOT NULL,
    "name" TEXT,
    "publicKey" TEXT,
    "lastSeenAt" TIMESTAMP(3),
    "lastSyncAt" TIMESTAMP(3),
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RegisteredDevice_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "EmergencyRequest" (
    "id" UUID NOT NULL,
    "clientRequestId" UUID NOT NULL,
    "createdByUserId" UUID NOT NULL,
    "facilityId" UUID NOT NULL,
    "requestedBloodGroup" "BloodGroup" NOT NULL,
    "urgency" "RequestUrgency" NOT NULL,
    "units" INTEGER NOT NULL,
    "status" "EmergencyRequestStatus" NOT NULL DEFAULT 'SEARCHING',
    "expiresAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "EmergencyRequest_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DonorResponse" (
    "id" UUID NOT NULL,
    "idempotencyKey" UUID NOT NULL,
    "requestId" UUID NOT NULL,
    "donorProfileId" UUID NOT NULL,
    "donorUserId" UUID NOT NULL,
    "status" "DonorResponseStatus" NOT NULL DEFAULT 'PENDING',
    "respondedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DonorResponse_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RequestActivity" (
    "id" UUID NOT NULL,
    "requestId" UUID NOT NULL,
    "actorUserId" UUID,
    "eventType" "RequestActivityType" NOT NULL,
    "fromStatus" "EmergencyRequestStatus",
    "toStatus" "EmergencyRequestStatus",
    "metadata" JSONB,
    "occurredAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RequestActivity_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DonationConfirmation" (
    "id" UUID NOT NULL,
    "idempotencyKey" UUID NOT NULL,
    "requestId" UUID NOT NULL,
    "donorProfileId" UUID NOT NULL,
    "facilityId" UUID NOT NULL,
    "confirmedByUserId" UUID NOT NULL,
    "consentedAt" TIMESTAMP(3),
    "receiptHash" TEXT NOT NULL,
    "confirmedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DonationConfirmation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Notification" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "idempotencyKey" UUID NOT NULL,
    "notificationType" TEXT NOT NULL,
    "status" "NotificationStatus" NOT NULL DEFAULT 'QUEUED',
    "sentAt" TIMESTAMP(3),
    "readAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Notification_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DeviceToken" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "token" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DeviceToken_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RefreshToken" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RefreshToken_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AuditLog" (
    "id" UUID NOT NULL,
    "actorUserId" UUID,
    "action" TEXT NOT NULL,
    "entityType" TEXT NOT NULL,
    "entityId" UUID,
    "metadata" JSONB,
    "occurredAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AuditLog_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SyncBatch" (
    "id" UUID NOT NULL,
    "registeredDeviceId" UUID NOT NULL,
    "clientBatchId" UUID NOT NULL,
    "status" "SyncBatchStatus" NOT NULL DEFAULT 'RECEIVED',
    "receivedCount" INTEGER NOT NULL DEFAULT 0,
    "acceptedCount" INTEGER NOT NULL DEFAULT 0,
    "rejectedCount" INTEGER NOT NULL DEFAULT 0,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completedAt" TIMESTAMP(3),

    CONSTRAINT "SyncBatch_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MeshMessage" (
    "id" UUID NOT NULL,
    "messageId" UUID NOT NULL,
    "registeredDeviceId" UUID,
    "requestId" UUID,
    "messageType" "MeshMessageType" NOT NULL,
    "contentHash" TEXT NOT NULL,
    "hopCount" INTEGER NOT NULL DEFAULT 0,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MeshMessage_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_phoneNumber_key" ON "User"("phoneNumber");

-- CreateIndex
CREATE INDEX "User_role_status_idx" ON "User"("role", "status");

-- CreateIndex
CREATE UNIQUE INDEX "DonorProfile_userId_key" ON "DonorProfile"("userId");

-- CreateIndex
CREATE INDEX "DonorProfile_bloodGroup_isAvailable_idx" ON "DonorProfile"("bloodGroup", "isAvailable");

-- CreateIndex
CREATE INDEX "DonorProfile_locality_idx" ON "DonorProfile"("locality");

-- CreateIndex
CREATE UNIQUE INDEX "HealthWorkerProfile_userId_key" ON "HealthWorkerProfile"("userId");

-- CreateIndex
CREATE INDEX "HealthWorkerProfile_facilityId_idx" ON "HealthWorkerProfile"("facilityId");

-- CreateIndex
CREATE INDEX "Facility_name_idx" ON "Facility"("name");

-- CreateIndex
CREATE INDEX "Facility_state_locality_idx" ON "Facility"("state", "locality");

-- CreateIndex
CREATE INDEX "RegisteredDevice_userId_revokedAt_idx" ON "RegisteredDevice"("userId", "revokedAt");

-- CreateIndex
CREATE UNIQUE INDEX "RegisteredDevice_userId_clientDeviceId_key" ON "RegisteredDevice"("userId", "clientDeviceId");

-- CreateIndex
CREATE UNIQUE INDEX "EmergencyRequest_clientRequestId_key" ON "EmergencyRequest"("clientRequestId");

-- CreateIndex
CREATE INDEX "EmergencyRequest_status_expiresAt_idx" ON "EmergencyRequest"("status", "expiresAt");

-- CreateIndex
CREATE INDEX "EmergencyRequest_requestedBloodGroup_status_idx" ON "EmergencyRequest"("requestedBloodGroup", "status");

-- CreateIndex
CREATE INDEX "EmergencyRequest_facilityId_createdAt_idx" ON "EmergencyRequest"("facilityId", "createdAt");

-- CreateIndex
CREATE INDEX "EmergencyRequest_createdByUserId_createdAt_idx" ON "EmergencyRequest"("createdByUserId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "DonorResponse_idempotencyKey_key" ON "DonorResponse"("idempotencyKey");

-- CreateIndex
CREATE INDEX "DonorResponse_requestId_status_idx" ON "DonorResponse"("requestId", "status");

-- CreateIndex
CREATE INDEX "DonorResponse_donorUserId_createdAt_idx" ON "DonorResponse"("donorUserId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "DonorResponse_requestId_donorProfileId_key" ON "DonorResponse"("requestId", "donorProfileId");

-- CreateIndex
CREATE INDEX "RequestActivity_requestId_occurredAt_idx" ON "RequestActivity"("requestId", "occurredAt");

-- CreateIndex
CREATE INDEX "RequestActivity_actorUserId_occurredAt_idx" ON "RequestActivity"("actorUserId", "occurredAt");

-- CreateIndex
CREATE UNIQUE INDEX "DonationConfirmation_idempotencyKey_key" ON "DonationConfirmation"("idempotencyKey");

-- CreateIndex
CREATE INDEX "DonationConfirmation_facilityId_confirmedAt_idx" ON "DonationConfirmation"("facilityId", "confirmedAt");

-- CreateIndex
CREATE INDEX "DonationConfirmation_confirmedByUserId_confirmedAt_idx" ON "DonationConfirmation"("confirmedByUserId", "confirmedAt");

-- CreateIndex
CREATE UNIQUE INDEX "DonationConfirmation_requestId_donorProfileId_key" ON "DonationConfirmation"("requestId", "donorProfileId");

-- CreateIndex
CREATE UNIQUE INDEX "Notification_idempotencyKey_key" ON "Notification"("idempotencyKey");

-- CreateIndex
CREATE INDEX "Notification_userId_status_createdAt_idx" ON "Notification"("userId", "status", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "DeviceToken_token_key" ON "DeviceToken"("token");

-- CreateIndex
CREATE INDEX "DeviceToken_userId_revokedAt_idx" ON "DeviceToken"("userId", "revokedAt");

-- CreateIndex
CREATE UNIQUE INDEX "RefreshToken_tokenHash_key" ON "RefreshToken"("tokenHash");

-- CreateIndex
CREATE INDEX "RefreshToken_userId_expiresAt_idx" ON "RefreshToken"("userId", "expiresAt");

-- CreateIndex
CREATE INDEX "RefreshToken_expiresAt_revokedAt_idx" ON "RefreshToken"("expiresAt", "revokedAt");

-- CreateIndex
CREATE INDEX "AuditLog_actorUserId_occurredAt_idx" ON "AuditLog"("actorUserId", "occurredAt");

-- CreateIndex
CREATE INDEX "AuditLog_entityType_entityId_occurredAt_idx" ON "AuditLog"("entityType", "entityId", "occurredAt");

-- CreateIndex
CREATE INDEX "SyncBatch_status_receivedAt_idx" ON "SyncBatch"("status", "receivedAt");

-- CreateIndex
CREATE INDEX "SyncBatch_registeredDeviceId_receivedAt_idx" ON "SyncBatch"("registeredDeviceId", "receivedAt");

-- CreateIndex
CREATE UNIQUE INDEX "SyncBatch_registeredDeviceId_clientBatchId_key" ON "SyncBatch"("registeredDeviceId", "clientBatchId");

-- CreateIndex
CREATE UNIQUE INDEX "MeshMessage_messageId_key" ON "MeshMessage"("messageId");

-- CreateIndex
CREATE INDEX "MeshMessage_requestId_receivedAt_idx" ON "MeshMessage"("requestId", "receivedAt");

-- CreateIndex
CREATE INDEX "MeshMessage_registeredDeviceId_receivedAt_idx" ON "MeshMessage"("registeredDeviceId", "receivedAt");

-- CreateIndex
CREATE INDEX "MeshMessage_messageType_receivedAt_idx" ON "MeshMessage"("messageType", "receivedAt");

-- AddForeignKey
ALTER TABLE "DonorProfile" ADD CONSTRAINT "DonorProfile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "HealthWorkerProfile" ADD CONSTRAINT "HealthWorkerProfile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "HealthWorkerProfile" ADD CONSTRAINT "HealthWorkerProfile_facilityId_fkey" FOREIGN KEY ("facilityId") REFERENCES "Facility"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RegisteredDevice" ADD CONSTRAINT "RegisteredDevice_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "EmergencyRequest" ADD CONSTRAINT "EmergencyRequest_createdByUserId_fkey" FOREIGN KEY ("createdByUserId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "EmergencyRequest" ADD CONSTRAINT "EmergencyRequest_facilityId_fkey" FOREIGN KEY ("facilityId") REFERENCES "Facility"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonorResponse" ADD CONSTRAINT "DonorResponse_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "EmergencyRequest"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonorResponse" ADD CONSTRAINT "DonorResponse_donorProfileId_fkey" FOREIGN KEY ("donorProfileId") REFERENCES "DonorProfile"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonorResponse" ADD CONSTRAINT "DonorResponse_donorUserId_fkey" FOREIGN KEY ("donorUserId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RequestActivity" ADD CONSTRAINT "RequestActivity_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "EmergencyRequest"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonationConfirmation" ADD CONSTRAINT "DonationConfirmation_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "EmergencyRequest"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonationConfirmation" ADD CONSTRAINT "DonationConfirmation_donorProfileId_fkey" FOREIGN KEY ("donorProfileId") REFERENCES "DonorProfile"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonationConfirmation" ADD CONSTRAINT "DonationConfirmation_facilityId_fkey" FOREIGN KEY ("facilityId") REFERENCES "Facility"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonationConfirmation" ADD CONSTRAINT "DonationConfirmation_confirmedByUserId_fkey" FOREIGN KEY ("confirmedByUserId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DeviceToken" ADD CONSTRAINT "DeviceToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RefreshToken" ADD CONSTRAINT "RefreshToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AuditLog" ADD CONSTRAINT "AuditLog_actorUserId_fkey" FOREIGN KEY ("actorUserId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SyncBatch" ADD CONSTRAINT "SyncBatch_registeredDeviceId_fkey" FOREIGN KEY ("registeredDeviceId") REFERENCES "RegisteredDevice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MeshMessage" ADD CONSTRAINT "MeshMessage_registeredDeviceId_fkey" FOREIGN KEY ("registeredDeviceId") REFERENCES "RegisteredDevice"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MeshMessage" ADD CONSTRAINT "MeshMessage_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "EmergencyRequest"("id") ON DELETE SET NULL ON UPDATE CASCADE;

