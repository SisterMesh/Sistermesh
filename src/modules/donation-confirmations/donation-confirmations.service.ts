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

    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }

    const replay = await this.prisma.donationConfirmation.findUnique({
      where: { idempotencyKey: dto.idempotencyKey },
    });
    if (replay) return this.assertSameConfirmation(replay, user.id, requestId, facilityId, dto);

    if (request.status !== EmergencyRequestStatus.ACCEPTED) {
      throw new ConflictException('Donation can only be confirmed for an accepted request.');
    }
    const response = await this.prisma.donorResponse.findUnique({
      where: {
        requestId_donorProfileId: { requestId, donorProfileId: dto.donorProfileId },
      },
    });
    if (!response || response.status !== DonorResponseStatus.ACCEPTED) {
      throw new ConflictException('The donor must have accepted this request before confirmation.');
    }

    try {
      return await this.prisma.$transaction(async (tx) => {
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
        await tx.donorResponse.update({
          where: { id: response.id },
          data: { status: DonorResponseStatus.CONFIRMED },
        });
        const changed = await tx.emergencyRequest.updateMany({
          where: { id: requestId, facilityId, status: EmergencyRequestStatus.ACCEPTED },
          data: { status: EmergencyRequestStatus.COMPLETED },
        });
        if (!changed.count) {
          throw new ConflictException('The request status changed before this donation was confirmed.');
        }
        await tx.requestActivity.create({
          data: {
            requestId,
            actorUserId: user.id,
            eventType: RequestActivityType.DONATION_CONFIRMED,
            fromStatus: EmergencyRequestStatus.ACCEPTED,
            toStatus: EmergencyRequestStatus.COMPLETED,
            metadata: {
              confirmationId: confirmation.id,
              donorProfileId: dto.donorProfileId,
              receiptHash: dto.receiptHash,
              requestedUnits: request.units,
            },
          },
        });
        return confirmation;
      });
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
