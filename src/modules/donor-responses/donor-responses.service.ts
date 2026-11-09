import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  BloodGroup,
  DonorResponseStatus,
  EmergencyRequestStatus,
  Prisma,
  RequestActivityType,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { canDonateTo } from '../donors/blood-compatibility';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';
import { WithdrawDonorResponseDto } from './dto/withdraw-donor-response.dto';
import { createSyncActivity } from '../offline-sync/sync-cursor';

@Injectable()
export class DonorResponsesService {
  constructor(private readonly prisma: PrismaService) {}

  async respond(user: AuthenticatedUser, requestId: string, dto: CreateDonorResponseDto) {
    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      include: { donorProfile: true },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.INDIVIDUAL || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    const replay = await this.prisma.donorResponse.findUnique({
      where: { idempotencyKey: dto.idempotencyKey },
    });
    if (replay) return this.assertSameResponse(replay, user.id, requestId, dto.status);
    if (!donor.donorProfile.isAvailable || !donor.donorProfile.bloodGroup) {
      throw new ForbiddenException('Your donor profile is currently unavailable.');
    }

    try {
      return await this.prisma.$transaction(async (tx) => {
        await tx.$queryRaw`SELECT "id" FROM "EmergencyRequest" WHERE "id" = ${requestId}::uuid FOR UPDATE`;
        const request = await tx.emergencyRequest.findUnique({ where: { id: requestId } });
        if (!request) throw new NotFoundException('Emergency request not found.');
        if (
          (request.status !== EmergencyRequestStatus.OPEN &&
            request.status !== EmergencyRequestStatus.PARTIALLY_FILLED) ||
          (request.expiresAt && request.expiresAt <= new Date())
        ) {
          throw new ConflictException('This emergency request is no longer accepting responses.');
        }
        const currentDonor = await tx.user.findUnique({
          where: { id: user.id },
          include: { donorProfile: true },
        });
        if (
          !currentDonor ||
          currentDonor.status !== 'ACTIVE' ||
          currentDonor.role !== UserRole.INDIVIDUAL ||
          !currentDonor.donorProfile ||
          !currentDonor.donorProfile.isAvailable ||
          !currentDonor.donorProfile.bloodGroup
        ) {
          throw new ForbiddenException('An active, available donor profile is required.');
        }
        if (!canDonateTo(currentDonor.donorProfile.bloodGroup, request.requestedBloodGroup as BloodGroup)) {
          throw new ForbiddenException('Your blood group is not compatible with this request.');
        }
        const priorResponse = await tx.donorResponse.findUnique({
          where: {
            requestId_donorProfileId: {
              requestId,
              donorProfileId: currentDonor.donorProfile.id,
            },
          },
        });
        if (priorResponse) {
          if (
            priorResponse.donorUserId === user.id &&
            priorResponse.idempotencyKey === dto.idempotencyKey &&
            priorResponse.submittedStatus === dto.status
          ) {
            return priorResponse;
          }
          throw new ConflictException('You have already responded to this emergency request.');
        }
        const eventType =
          dto.status === DonorResponseStatus.ACCEPTED
            ? RequestActivityType.DONOR_ACCEPTED
            : RequestActivityType.DONOR_DECLINED;
        const response = await tx.donorResponse.create({
          data: {
            idempotencyKey: dto.idempotencyKey,
            submittedStatus: dto.status,
            requestId,
            donorProfileId: currentDonor.donorProfile.id,
            donorUserId: user.id,
            status: dto.status,
            respondedAt: new Date(),
          },
        });
        await createSyncActivity(tx, {
            requestId,
            actorUserId: user.id,
            eventType,
            metadata: { responseId: response.id },
        });
        return response;
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        const duplicate = await this.prisma.donorResponse.findUnique({
          where: { idempotencyKey: dto.idempotencyKey },
        });
        if (duplicate) return this.assertSameResponse(duplicate, user.id, requestId, dto.status);
        throw new ConflictException('You have already responded to this emergency request.');
      }
      throw error;
    }
  }

  async withdraw(user: AuthenticatedUser, requestId: string, dto: WithdrawDonorResponseDto) {
    const replay = await this.prisma.donorResponse.findUnique({
      where: { withdrawalIdempotencyKey: dto.idempotencyKey },
    });
    if (replay) return this.assertSameWithdrawal(replay, user.id, requestId);

    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, role: true, status: true, donorProfile: { select: { id: true } } },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.INDIVIDUAL || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    try {
      return await this.prisma.$transaction(async (tx) => {
        await tx.$queryRaw`SELECT "id" FROM "EmergencyRequest" WHERE "id" = ${requestId}::uuid FOR UPDATE`;
        const request = await tx.emergencyRequest.findUnique({ where: { id: requestId } });
        if (!request) throw new NotFoundException('Emergency request not found.');
        if (
          (request.status !== EmergencyRequestStatus.OPEN &&
            request.status !== EmergencyRequestStatus.PARTIALLY_FILLED) ||
          (request.expiresAt && request.expiresAt <= new Date())
        ) {
          throw new ConflictException('A response cannot be withdrawn after the request closes.');
        }
        const response = await tx.donorResponse.findUnique({
          where: {
            requestId_donorProfileId: { requestId, donorProfileId: donor.donorProfile!.id },
          },
        });
        if (!response || response.donorUserId !== user.id) {
          throw new NotFoundException('Your response for this request was not found.');
        }
        if (response.status === DonorResponseStatus.WITHDRAWN) {
          if (response.withdrawalIdempotencyKey === dto.idempotencyKey) return response;
          throw new ConflictException('This response has already been withdrawn.');
        }
        if (response.status !== DonorResponseStatus.ACCEPTED) {
          throw new ConflictException('Only an accepted response can be withdrawn.');
        }
        const updated = await tx.donorResponse.update({
          where: { id: response.id },
          data: {
            status: DonorResponseStatus.WITHDRAWN,
            withdrawalIdempotencyKey: dto.idempotencyKey,
            withdrawnAt: new Date(),
          },
        });
        await createSyncActivity(tx, {
            requestId,
            actorUserId: user.id,
            eventType: RequestActivityType.DONOR_WITHDRAWN,
            metadata: { responseId: response.id },
        });
        return updated;
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        const duplicate = await this.prisma.donorResponse.findUnique({
          where: { withdrawalIdempotencyKey: dto.idempotencyKey },
        });
        if (duplicate) return this.assertSameWithdrawal(duplicate, user.id, requestId);
        throw new ConflictException('This response has already been changed.');
      }
      throw error;
    }
  }

  async listMine(user: AuthenticatedUser) {
    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, role: true, status: true, donorProfile: { select: { id: true } } },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.INDIVIDUAL || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    return this.prisma.donorResponse.findMany({
      where: { donorUserId: user.id },
      select: {
        id: true,
        requestId: true,
        donorProfileId: true,
        status: true,
        respondedAt: true,
        withdrawnAt: true,
        createdAt: true,
        request: {
          select: {
            id: true,
            requestedBloodGroup: true,
            urgency: true,
            units: true,
            status: true,
            expiresAt: true,
            createdAt: true,
            facility: { select: { id: true, name: true, locality: true, state: true } },
          },
        },
      },
      orderBy: { createdAt: 'desc' },
    });
  }

  async listForRequest(user: AuthenticatedUser, requestId: string) {
    const worker = await this.prisma.user.findUnique({
      where: { id: user.id },
      include: { healthWorkerProfile: { include: { facility: { select: { status: true } } } } },
    });
    if (
      !worker ||
      worker.status !== 'ACTIVE' ||
      worker.role !== UserRole.HEALTH_WORKER ||
      worker.healthWorkerProfile?.verificationStatus !== 'VERIFIED' ||
      !worker.healthWorkerProfile.facilityId ||
      worker.healthWorkerProfile.facility?.status !== 'ACTIVE'
    ) {
      throw new ForbiddenException('An active, verified health worker assigned to an active facility is required.');
    }
    const request = await this.prisma.emergencyRequest.findUnique({
      where: { id: requestId },
      select: { facilityId: true },
    });
    if (!request || request.facilityId !== worker.healthWorkerProfile.facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    return this.prisma.donorResponse.findMany({
      where: { requestId },
      select: {
        id: true,
        status: true,
        respondedAt: true,
        createdAt: true,
        donorProfile: { select: { id: true, bloodGroup: true } },
      },
      orderBy: { createdAt: 'asc' },
    });
  }

  private assertSameResponse(
    existing: {
      id: string;
      donorUserId: string;
      requestId: string;
      status: DonorResponseStatus;
      submittedStatus?: DonorResponseStatus | null;
    },
    userId: string,
    requestId: string,
    status: DonorResponseStatus,
  ) {
    if (
      existing.donorUserId !== userId ||
      existing.requestId !== requestId ||
      (existing.submittedStatus ?? existing.status) !== status
    ) {
      throw new ConflictException('This idempotency key was already used for different response data.');
    }
    return existing;
  }

  private assertSameWithdrawal(
    existing: {
      id: string;
      donorUserId: string;
      requestId: string;
      status: DonorResponseStatus;
    },
    userId: string,
    requestId: string,
  ) {
    if (existing.donorUserId !== userId || existing.requestId !== requestId) {
      throw new ConflictException('This idempotency key was already used for different withdrawal data.');
    }
    return existing;
  }
}
