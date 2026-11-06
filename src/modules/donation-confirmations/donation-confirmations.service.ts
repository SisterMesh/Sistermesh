import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  DonorResponseStatus,
  EmergencyRequestStatus,
  Prisma,
  RequestActivityType,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonationConfirmationDto } from './dto/create-donation-confirmation.dto';
import { createSyncActivity } from '../offline-sync/sync-cursor';

@Injectable()
export class DonationConfirmationsService {
  constructor(private readonly prisma: PrismaService) {}

  async confirm(user: AuthenticatedUser, requestId: string, dto: CreateDonationConfirmationDto) {
    const worker = await this.prisma.user.findUnique({
      where: { id: user.id },
      include: {
        healthWorkerProfile: {
          include: { facility: { select: { status: true } } },
        },
      },
    });
    if (!worker || worker.status !== 'ACTIVE' || worker.role !== UserRole.HEALTH_WORKER) {
      throw new ForbiddenException('An active health worker account is required.');
    }
    if (worker.healthWorkerProfile?.verificationStatus !== 'VERIFIED') {
      throw new ForbiddenException('Your health worker profile has not been verified.');
    }
    const facilityId = worker.healthWorkerProfile?.facilityId;
    if (!facilityId) throw new ForbiddenException('Your account is not assigned to a facility.');
    if (worker.healthWorkerProfile.facility?.status !== 'ACTIVE') {
      throw new ForbiddenException('Your assigned facility is not active.');
    }

    const replay = await this.prisma.donationConfirmation.findUnique({
      where: { idempotencyKey: dto.idempotencyKey },
    });
    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    if (replay) return this.assertSameConfirmation(replay, user.id, requestId, facilityId, dto);

    try {
      const result = await this.prisma.$transaction(async (tx) => {
        await tx.$queryRaw`SELECT "id" FROM "EmergencyRequest" WHERE "id" = ${requestId}::uuid FOR UPDATE`;
        const lockedRequest = await tx.emergencyRequest.findUnique({ where: { id: requestId } });
        if (!lockedRequest || lockedRequest.facilityId !== facilityId) {
          throw new NotFoundException('Emergency request not found.');
        }
        const currentWorker = await tx.user.findUnique({
          where: { id: user.id },
          include: { healthWorkerProfile: { include: { facility: { select: { status: true } } } } },
        });
        if (
          !currentWorker ||
          currentWorker.status !== 'ACTIVE' ||
          currentWorker.role !== UserRole.HEALTH_WORKER ||
          currentWorker.healthWorkerProfile?.verificationStatus !== 'VERIFIED' ||
          currentWorker.healthWorkerProfile.facilityId !== facilityId ||
          currentWorker.healthWorkerProfile.facility?.status !== 'ACTIVE'
        ) {
          throw new ForbiddenException('An active, verified health worker assigned to this facility is required.');
        }
        const transactionReplay = await tx.donationConfirmation.findUnique({
          where: { idempotencyKey: dto.idempotencyKey },
        });
        if (transactionReplay) {
          return { confirmation: this.assertSameConfirmation(transactionReplay, user.id, requestId, facilityId, dto) };
        }
        const now = new Date();
        if (
          (lockedRequest.status === EmergencyRequestStatus.OPEN ||
            lockedRequest.status === EmergencyRequestStatus.PARTIALLY_FILLED) &&
          lockedRequest.expiresAt &&
          lockedRequest.expiresAt <= now
        ) {
          await tx.emergencyRequest.update({
            where: { id: requestId },
            data: { status: EmergencyRequestStatus.EXPIRED },
          });
          await createSyncActivity(tx, {
              requestId,
              eventType: RequestActivityType.EXPIRED,
              fromStatus: lockedRequest.status,
              toStatus: EmergencyRequestStatus.EXPIRED,
          });
          return { expired: true as const };
        }
        if (
          lockedRequest.status !== EmergencyRequestStatus.OPEN &&
          lockedRequest.status !== EmergencyRequestStatus.PARTIALLY_FILLED
        ) {
          throw new ConflictException('Donation can only be confirmed while the request is open.');
        }
        const response = await tx.donorResponse.findUnique({
          where: {
            requestId_donorProfileId: { requestId, donorProfileId: dto.donorProfileId },
          },
        });
        if (!response || response.status !== DonorResponseStatus.ACCEPTED) {
          throw new ConflictException('The donor must have an accepted response before confirmation.');
        }
        const confirmedCount = await tx.donationConfirmation.count({ where: { requestId } });
        if (confirmedCount >= lockedRequest.units) {
          throw new ConflictException('The required number of donations has already been confirmed.');
        }
        const confirmation = await tx.donationConfirmation.create({
          data: {
            idempotencyKey: dto.idempotencyKey,
            requestId,
            donorProfileId: dto.donorProfileId,
            facilityId,
            confirmedByUserId: user.id,
            receiptHash: dto.receiptHash,
            consentedAt: dto.consentedAt ? new Date(dto.consentedAt) : null,
          },
        });
        const responseChanged = await tx.donorResponse.updateMany({
          where: { id: response.id, status: DonorResponseStatus.ACCEPTED },
          data: { status: DonorResponseStatus.CONFIRMED },
        });
        if (!responseChanged.count) {
          throw new ConflictException('The donor response changed before confirmation.');
        }
        const nextCount = confirmedCount + 1;
        const nextStatus =
          nextCount >= lockedRequest.units
            ? EmergencyRequestStatus.FULFILLED
            : EmergencyRequestStatus.PARTIALLY_FILLED;
        const changed = await tx.emergencyRequest.updateMany({
          where: {
            id: requestId,
            facilityId,
            status: { in: [EmergencyRequestStatus.OPEN, EmergencyRequestStatus.PARTIALLY_FILLED] },
          },
          data: { status: nextStatus },
        });
        if (!changed.count) {
          throw new ConflictException('The request status changed before this donation was confirmed.');
        }
        await createSyncActivity(tx, {
            requestId,
            actorUserId: user.id,
            eventType: RequestActivityType.DONATION_CONFIRMED,
            fromStatus: lockedRequest.status,
            toStatus: nextStatus,
            metadata: {
              confirmationId: confirmation.id,
              donationNumber: nextCount,
              requiredDonations: lockedRequest.units,
            },
        });
        if (nextStatus !== lockedRequest.status) {
          await createSyncActivity(tx, {
              requestId,
              actorUserId: user.id,
              eventType:
                nextStatus === EmergencyRequestStatus.FULFILLED
                  ? RequestActivityType.FULFILLED
                  : RequestActivityType.PARTIALLY_FILLED,
              fromStatus: lockedRequest.status,
              toStatus: nextStatus,
          });
        }
        return { confirmation };
      });
      if ('expired' in result) {
        throw new ConflictException('This emergency request has expired.');
      }
      return result.confirmation;
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        const duplicate = await this.prisma.donationConfirmation.findUnique({
          where: { idempotencyKey: dto.idempotencyKey },
        });
        if (duplicate) return this.assertSameConfirmation(duplicate, user.id, requestId, facilityId, dto);
        throw new ConflictException('A donation for this donor and request was already confirmed.');
      }
      throw error;
    }
  }

  private assertSameConfirmation(
    existing: {
      id: string;
      confirmedByUserId: string;
      requestId: string;
      facilityId: string;
      donorProfileId: string;
      receiptHash: string;
      consentedAt: Date | null;
    },
    userId: string,
    requestId: string,
    facilityId: string,
    dto: CreateDonationConfirmationDto,
  ) {
    const consentedAt = dto.consentedAt ? new Date(dto.consentedAt).getTime() : null;
    if (
      existing.confirmedByUserId !== userId ||
      existing.requestId !== requestId ||
      existing.facilityId !== facilityId ||
      existing.donorProfileId !== dto.donorProfileId ||
      existing.receiptHash !== dto.receiptHash ||
      (existing.consentedAt?.getTime() ?? null) !== consentedAt
    ) {
      throw new ConflictException('This idempotency key was already used for different confirmation data.');
    }
    return existing;
  }
}
