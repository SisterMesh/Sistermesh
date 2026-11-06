import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
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

const ALLOWED_TRANSITIONS: Partial<Record<EmergencyRequestStatus, EmergencyRequestStatus[]>> = {
  [EmergencyRequestStatus.SEARCHING]: [EmergencyRequestStatus.WAITING, EmergencyRequestStatus.ACCEPTED, EmergencyRequestStatus.EXPIRED],
  [EmergencyRequestStatus.WAITING]: [EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.ACCEPTED, EmergencyRequestStatus.EXPIRED],
  [EmergencyRequestStatus.ACCEPTED]: [EmergencyRequestStatus.COMPLETED],
};

@Injectable()
export class EmergencyRequestsService {
  constructor(private readonly prisma: PrismaService) {}

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
        status: { in: [EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.WAITING] },
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
    const facilityId = await this.getWorkerFacility(user);
    return this.prisma.emergencyRequest.findMany({
      where: { facilityId },
      include: { facility: true },
      orderBy: { createdAt: 'desc' },
    });
  }

  async updateStatus(user: AuthenticatedUser, requestId: string, status: EmergencyRequestStatus) {
    const facilityId = await this.getWorkerFacility(user);
    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    if (!ALLOWED_TRANSITIONS[request.status]?.includes(status)) {
      throw new ConflictException(`Cannot change request from ${request.status} to ${status}.`);
    }
    return this.prisma.$transaction(async (tx) => {
      const changed = await tx.emergencyRequest.updateMany({
        where: { id: request.id, status: request.status },
        data: { status },
      });
      if (!changed.count) {
        throw new ConflictException('The request status changed before this update.');
      }
      const updated = await tx.emergencyRequest.findUnique({
        where: { id: request.id },
        include: { facility: true },
      });
      await tx.requestActivity.create({
        data: {
          requestId,
          actorUserId: user.id,
          eventType: status === EmergencyRequestStatus.EXPIRED ? RequestActivityType.EXPIRED : RequestActivityType.STATUS_CHANGED,
          fromStatus: request.status,
          toStatus: status,
        },
      });
      return updated;
    });
  }

  async cancel(user: AuthenticatedUser, requestId: string) {
    const facilityId = await this.getWorkerFacility(user);
    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    if (![EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.WAITING, EmergencyRequestStatus.ACCEPTED].some((status) => status === request.status)) {
      throw new ConflictException(`Cannot cancel a request in ${request.status} status.`);
    }
    return this.prisma.$transaction(async (tx) => {
      const changed = await tx.emergencyRequest.updateMany({
        where: { id: request.id, status: request.status },
        data: { status: EmergencyRequestStatus.CANCELLED },
      });
      if (!changed.count) {
        throw new ConflictException('The request status changed before cancellation.');
      }
      const updated = await tx.emergencyRequest.findUnique({
        where: { id: request.id },
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
      return updated;
    });
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
