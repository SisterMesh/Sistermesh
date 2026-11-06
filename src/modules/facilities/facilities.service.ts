import {
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  FacilityStatus,
  HealthWorkerVerificationStatus,
  Prisma,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateFacilityDto } from './dto/create-facility.dto';
import { SetFacilityStatusDto } from './dto/set-facility-status.dto';
import { UpdateFacilityDto } from './dto/update-facility.dto';

const facilitySelect = {
  id: true,
  name: true,
  facilityType: true,
  address: true,
  locality: true,
  localGovernmentArea: true,
  state: true,
  contactPhone: true,
  status: true,
  createdAt: true,
  updatedAt: true,
} satisfies Prisma.FacilitySelect;

@Injectable()
export class FacilitiesService {
  constructor(private readonly prisma: PrismaService) {}

  async listForUser(user: AuthenticatedUser) {
    return this.prisma.facility.findMany({
      where: user.role === UserRole.ADMIN ? {} : { status: FacilityStatus.ACTIVE },
      orderBy: [{ state: 'asc' }, { name: 'asc' }],
      select: facilitySelect,
    });
  }

  async getForUser(facilityId: string, user: AuthenticatedUser) {
    const facility = await this.prisma.facility.findUnique({
      where: { id: facilityId },
      select: facilitySelect,
    });
    if (!facility) throw new NotFoundException('Facility not found.');
    if (user.role === UserRole.ADMIN) return facility;
    if (facility.status !== FacilityStatus.ACTIVE) {
      throw new NotFoundException('Facility not found.');
    }
    if (user.role === UserRole.HEALTH_WORKER) {
      const profile = await this.prisma.healthWorkerProfile.findUnique({
        where: { userId: user.id },
        select: { facilityId: true, verificationStatus: true },
      });
      if (
        profile?.facilityId !== facilityId ||
        profile.verificationStatus !== HealthWorkerVerificationStatus.VERIFIED
      ) {
        throw new ForbiddenException(
          'Verified health workers may only access their assigned facility.',
        );
      }
    }
    return facility;
  }

  async create(actorId: string, dto: CreateFacilityDto) {
    return this.prisma.$transaction(async (tx) => {
      const facility = await tx.facility.create({
        data: { ...dto, status: FacilityStatus.PENDING },
        select: facilitySelect,
      });
      await tx.auditLog.create({
        data: {
          actorUserId: actorId,
          action: 'FACILITY_CREATED',
          entityType: 'Facility',
          entityId: facility.id,
          metadata: { status: facility.status },
        },
      });
      return facility;
    });
  }

  async update(actorId: string, facilityId: string, dto: UpdateFacilityDto) {
    try {
      return await this.prisma.$transaction(async (tx) => {
        const facility = await tx.facility.update({
          where: { id: facilityId },
          data: dto,
          select: facilitySelect,
        });
        await tx.auditLog.create({
          data: {
            actorUserId: actorId,
            action: 'FACILITY_UPDATED',
            entityType: 'Facility',
            entityId: facilityId,
          },
        });
        return facility;
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2025'
      ) {
        throw new NotFoundException('Facility not found.');
      }
      throw error;
    }
  }

  async setStatus(
    actorId: string,
    facilityId: string,
    status: SetFacilityStatusDto['status'],
  ) {
    try {
      return await this.prisma.$transaction(async (tx) => {
        const facility = await tx.facility.update({
          where: { id: facilityId },
          data: { status },
          select: facilitySelect,
        });
        await tx.auditLog.create({
          data: {
            actorUserId: actorId,
            action: 'FACILITY_STATUS_CHANGED',
            entityType: 'Facility',
            entityId: facilityId,
            metadata: { status },
          },
        });
        return facility;
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2025'
      ) {
        throw new NotFoundException('Facility not found.');
      }
      throw error;
    }
  }

  async getHealthWorkers(facilityId: string) {
    const facility = await this.prisma.facility.findUnique({
      where: { id: facilityId },
      select: {
        id: true,
        name: true,
        healthWorkers: {
          orderBy: { createdAt: 'asc' },
          select: {
            userId: true,
            designation: true,
            verificationStatus: true,
            user: { select: { displayName: true, status: true } },
          },
        },
      },
    });
    if (!facility) throw new NotFoundException('Facility not found.');
    return facility;
  }
}
