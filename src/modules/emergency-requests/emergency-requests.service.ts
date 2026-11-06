import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  Injectable,
  Logger,
  NotFoundException,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import {
  BloodGroup,
  EmergencyRequestStatus,
  Prisma,
  RequestActivityType,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { DONOR_GROUPS_FOR_RECIPIENT } from '../donors/blood-compatibility';
import { PrismaService } from '../prisma/prisma.service';
import { CreateEmergencyRequestDto } from './dto/create-emergency-request.dto';
import { UpdateEmergencyRequestDto } from './dto/update-emergency-request.dto';

@Injectable()
export class EmergencyRequestsService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(EmergencyRequestsService.name);
  private expiryTimer?: NodeJS.Timeout;

  constructor(private readonly prisma: PrismaService) {}

  onModuleInit() {
    this.expiryTimer = setInterval(() => {
      void this.expireDueRequests().catch((error: unknown) => {
        this.logger.error('Failed to expire due emergency requests.', error);
      });
    }, 60_000);
    this.expiryTimer.unref();
    void this.expireDueRequests().catch((error: unknown) => {
      this.logger.error('Failed to expire due emergency requests at startup.', error);
    });
  }

  onModuleDestroy() {
    if (this.expiryTimer) clearInterval(this.expiryTimer);
  }

  async create(user: AuthenticatedUser, dto: CreateEmergencyRequestDto) {
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
    if (!facilityId) {
      throw new ForbiddenException('Your account is not assigned to a facility.');
    }
    if (worker.healthWorkerProfile.facility?.status !== 'ACTIVE') {
      throw new ForbiddenException('Your assigned facility is not active.');
    }

    const expiresAt = dto.expiresAt ? new Date(dto.expiresAt) : null;
    const existing = await this.prisma.emergencyRequest.findUnique({
      where: { clientRequestId: dto.clientRequestId },
      include: { facility: true },
    });
    if (existing) return this.assertSameCreate(existing, user.id, facilityId, dto, expiresAt);
    if (expiresAt && expiresAt <= new Date()) {
      throw new BadRequestException('Request expiry must be in the future.');
    }

    try {
      return await this.prisma.$transaction(async (tx) => {
        const request = await tx.emergencyRequest.create({
          data: {
            clientRequestId: dto.clientRequestId,
            createdByUserId: user.id,
            facilityId,
            requestedBloodGroup: dto.requestedBloodGroup,
            urgency: dto.urgency,
            units: dto.units,
            expiresAt,
          },
          include: { facility: true },
        });
        await tx.requestActivity.create({
          data: {
            requestId: request.id,
            actorUserId: user.id,
            eventType: RequestActivityType.CREATED,
            toStatus: request.status,
            metadata: { facilityId, units: dto.units },
          },
        });
        return request;
      });
    } catch (error) {
      if (this.isUniqueConstraint(error)) {
        const duplicate = await this.prisma.emergencyRequest.findUnique({
          where: { clientRequestId: dto.clientRequestId },
          include: { facility: true },
        });
        if (duplicate) return this.assertSameCreate(duplicate, user.id, facilityId, dto, expiresAt);
      }
      throw error;
    }
  }

  async listEligible(user: AuthenticatedUser) {
    await this.expireDueRequests();
    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      include: { donorProfile: true },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.DONOR || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    const donorBloodGroup = donor.donorProfile.bloodGroup;
    if (!donor.donorProfile.isAvailable || !donorBloodGroup) return [];

    const now = new Date();
    return this.prisma.emergencyRequest.findMany({
      where: {
        requestedBloodGroup: {
          in: Object.entries(DONOR_GROUPS_FOR_RECIPIENT)
            .filter(([, donors]) => donors.includes(donorBloodGroup))
            .map(([recipient]) => recipient as BloodGroup),
        },
        status: { in: [EmergencyRequestStatus.OPEN, EmergencyRequestStatus.PARTIALLY_FILLED] },
        OR: [{ expiresAt: null }, { expiresAt: { gt: now } }],
      },
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
      orderBy: [{ urgency: 'desc' }, { createdAt: 'desc' }],
    });
  }

  async listForFacility(user: AuthenticatedUser) {
    await this.expireDueRequests();
    const facilityId = await this.getWorkerFacility(user);
    return this.prisma.emergencyRequest.findMany({
      where: { facilityId },
      include: { facility: true },
      orderBy: { createdAt: 'desc' },
    });
  }

  async update(user: AuthenticatedUser, requestId: string, dto: UpdateEmergencyRequestDto) {
    const facilityId = await this.getWorkerFacility(user);
    const expiresAt = dto.expiresAt === undefined
      ? undefined
      : dto.expiresAt === null
        ? null
        : new Date(dto.expiresAt);
    if (expiresAt && expiresAt <= new Date()) {
      throw new BadRequestException('Request expiry must be in the future.');
    }

    const result = await this.prisma.$transaction(async (tx) => {
      const request = await this.lockRequest(tx, requestId);
      if (!request || request.facilityId !== facilityId) {
        throw new NotFoundException('Emergency request not found.');
      }
      const now = new Date();
      if (await this.expireLockedRequest(tx, request, now)) return { expired: true as const };
      if (!this.isOpen(request.status)) {
        throw new ConflictException(`Cannot update a request in ${request.status} status.`);
      }
      const confirmedCount = await tx.donationConfirmation.count({ where: { requestId } });
      const units = dto.units ?? request.units;
      if (units < confirmedCount) {
        throw new ConflictException('The required donation count cannot be lower than donations already confirmed.');
      }
      const data: Prisma.EmergencyRequestUpdateInput = {};
      if (dto.requestedBloodGroup !== undefined) data.requestedBloodGroup = dto.requestedBloodGroup;
      if (dto.urgency !== undefined) data.urgency = dto.urgency;
      if (dto.units !== undefined) data.units = dto.units;
      if (expiresAt !== undefined) data.expiresAt = expiresAt;
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data,
        include: { facility: true },
      });
      await tx.requestActivity.create({
        data: {
          requestId,
          actorUserId: user.id,
          eventType: RequestActivityType.UPDATED,
          fromStatus: request.status,
          toStatus: request.status,
          metadata: { fields: Object.keys(data) },
        },
      });
      if (confirmedCount > 0 && confirmedCount >= updated.units && request.status !== EmergencyRequestStatus.FULFILLED) {
        const fulfilled = await tx.emergencyRequest.update({
          where: { id: requestId },
          data: { status: EmergencyRequestStatus.FULFILLED },
          include: { facility: true },
        });
        await this.recordStatusTransition(tx, requestId, user.id, request.status, EmergencyRequestStatus.FULFILLED);
        return { request: fulfilled };
      }
      return { request: updated };
    });
    if ('expired' in result) throw new ConflictException('This emergency request has expired.');
    return result.request;
  }

  async cancel(user: AuthenticatedUser, requestId: string) {
    const facilityId = await this.getWorkerFacility(user);
    const result = await this.prisma.$transaction(async (tx) => {
      const request = await this.lockRequest(tx, requestId);
      if (!request || request.facilityId !== facilityId) {
        throw new NotFoundException('Emergency request not found.');
      }
      if (request.status === EmergencyRequestStatus.CANCELLED) {
        return { request: await tx.emergencyRequest.findUnique({ where: { id: requestId }, include: { facility: true } }) };
      }
      const now = new Date();
      if (await this.expireLockedRequest(tx, request, now)) return { expired: true as const };
      if (!this.isOpen(request.status)) {
        throw new ConflictException(`Cannot cancel a request in ${request.status} status.`);
      }
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data: { status: EmergencyRequestStatus.CANCELLED },
        include: { facility: true },
      });
      await tx.requestActivity.create({
        data: {
          requestId,
          actorUserId: user.id,
          eventType: RequestActivityType.CANCELLED,
          fromStatus: request.status,
          toStatus: EmergencyRequestStatus.CANCELLED,
        },
      });
      return { request: updated };
    });
    if ('expired' in result) throw new ConflictException('This emergency request has expired.');
    return result.request;
  }

  async history(user: AuthenticatedUser, requestId: string) {
    const facilityId = await this.getWorkerFacility(user);
    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId }, select: { facilityId: true } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    return this.prisma.requestActivity.findMany({
      where: { requestId },
      orderBy: { occurredAt: 'asc' },
    });
  }

  private async getWorkerFacility(user: AuthenticatedUser): Promise<string> {
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
    if (!worker.healthWorkerProfile?.facilityId) {
      throw new ForbiddenException('Your account is not assigned to a facility.');
    }
    if (worker.healthWorkerProfile.facility?.status !== 'ACTIVE') {
      throw new ForbiddenException('Your assigned facility is not active.');
    }
    return worker.healthWorkerProfile.facilityId;
  }

  private assertSameCreate(
    existing: { id: string; createdByUserId: string; facilityId: string; requestedBloodGroup: BloodGroup; urgency: string; units: number; expiresAt: Date | null },
    userId: string,
    facilityId: string,
    dto: CreateEmergencyRequestDto,
    expiresAt: Date | null,
  ) {
    const same =
      existing.createdByUserId === userId &&
      existing.facilityId === facilityId &&
      existing.requestedBloodGroup === dto.requestedBloodGroup &&
      existing.urgency === dto.urgency &&
      existing.units === dto.units &&
      (existing.expiresAt?.getTime() ?? null) === (expiresAt?.getTime() ?? null);
    if (!same) throw new ConflictException('This client request ID was already used for different request data.');
    return existing;
  }

  private isUniqueConstraint(error: unknown): error is Prisma.PrismaClientKnownRequestError {
    return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
  }
}
