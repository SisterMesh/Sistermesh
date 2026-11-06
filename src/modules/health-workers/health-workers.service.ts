import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  AccountStatus,
  FacilityStatus,
  HealthWorkerVerificationStatus,
  Prisma,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { AssignWorkerFacilityDto } from './dto/assign-worker-facility.dto';
import { CreateHealthWorkerProfileDto } from './dto/create-health-worker-profile.dto';
import { ListHealthWorkersQueryDto } from './dto/list-health-workers-query.dto';
import { UpdateHealthWorkerProfileDto } from './dto/update-health-worker-profile.dto';
import { UpdateWorkerVerificationDto } from './dto/update-worker-verification.dto';

@Injectable()
export class HealthWorkersService {
  constructor(private readonly prisma: PrismaService) {}

  async createMine(user: AuthenticatedUser, dto: CreateHealthWorkerProfileDto) {
    await this.assertHealthWorker(user.id);
    try {
      return await this.prisma.healthWorkerProfile.create({
        data: {
          userId: user.id,
          designation: dto.designation?.trim() || null,
          professionalId: dto.professionalId?.trim() || null,
          verificationStatus: HealthWorkerVerificationStatus.PENDING,
        },
        select: this.profileSelect(),
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new ConflictException('A health worker profile or professional ID already exists.');
      }
      throw error;
    }
  }

  async getMine(user: AuthenticatedUser) {
    await this.assertHealthWorker(user.id);
    const profile = await this.prisma.healthWorkerProfile.findUnique({
      where: { userId: user.id },
      select: this.profileSelect(),
    });
    if (!profile) throw new NotFoundException('Health worker profile not found.');
    return profile;
  }

  async updateMine(user: AuthenticatedUser, dto: UpdateHealthWorkerProfileDto) {
    await this.assertHealthWorker(user.id);
    const current = await this.prisma.healthWorkerProfile.findUnique({ where: { userId: user.id } });
    if (!current) throw new NotFoundException('Health worker profile not found.');
    const professionalId =
      dto.professionalId === undefined ? current.professionalId : dto.professionalId?.trim() || null;
    const changedId = professionalId !== current.professionalId;
    try {
      return await this.prisma.healthWorkerProfile.update({
        where: { userId: user.id },
        data: {
          ...(dto.designation !== undefined ? { designation: dto.designation.trim() || null } : {}),
          ...(dto.professionalId !== undefined ? { professionalId } : {}),
          ...(changedId
            ? {
                verificationStatus: HealthWorkerVerificationStatus.PENDING,
                verifiedAt: null,
                facilityId: null,
              }
            : {}),
        },
        select: this.profileSelect(),
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new ConflictException('That professional ID is already assigned to another worker.');
      }
      throw error;
    }
  }

  async getMyFacility(user: AuthenticatedUser) {
    await this.assertHealthWorker(user.id);
    const profile = await this.prisma.healthWorkerProfile.findUnique({
      where: { userId: user.id },
      include: { facility: true },
    });
    if (
      !profile ||
      profile.verificationStatus !== HealthWorkerVerificationStatus.VERIFIED ||
      !profile.facility ||
      profile.facility.status !== FacilityStatus.ACTIVE
    ) {
      throw new NotFoundException('No active authorized facility is assigned to this account.');
    }
    return profile.facility;
  }

  async setVerification(admin: AuthenticatedUser, userId: string, dto: UpdateWorkerVerificationDto) {
    const worker = await this.prisma.user.findUnique({
      where: { id: userId },
      include: { healthWorkerProfile: true },
    });
    if (
      !worker ||
      worker.role !== UserRole.HEALTH_WORKER ||
      worker.status !== AccountStatus.ACTIVE ||
      !worker.healthWorkerProfile
    ) {
      throw new NotFoundException('Active health worker profile not found.');
    }
    const workerProfile = worker.healthWorkerProfile;
    if (
      dto.verificationStatus === HealthWorkerVerificationStatus.VERIFIED &&
      !workerProfile.professionalId
    ) {
      throw new ConflictException('A professional ID is required before verification.');
    }

    return this.prisma.$transaction(async (tx) => {
      const updated = await tx.healthWorkerProfile.update({
        where: { userId },
        data: {
          verificationStatus: dto.verificationStatus,
          verifiedAt:
            dto.verificationStatus === HealthWorkerVerificationStatus.VERIFIED ? new Date() : null,
          ...(dto.verificationStatus !== HealthWorkerVerificationStatus.VERIFIED
            ? { facilityId: null }
            : {}),
        },
        select: this.profileSelect(),
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: `HEALTH_WORKER_${dto.verificationStatus}`,
          entityType: 'HealthWorkerProfile',
          entityId: workerProfile.id,
          metadata: { userId },
        },
      });
      return updated;
    });
  }

  listForAdmin(query: ListHealthWorkersQueryDto) {
    return this.prisma.healthWorkerProfile.findMany({
      where: {
        verificationStatus: query.verificationStatus ?? HealthWorkerVerificationStatus.PENDING,
      },
      select: {
        id: true,
        designation: true,
        professionalId: true,
        verificationStatus: true,
        verifiedAt: true,
        createdAt: true,
        user: { select: { id: true, displayName: true, phoneNumber: true, status: true } },
      },
      orderBy: { createdAt: 'asc' },
    });
  }

  async assignFacility(admin: AuthenticatedUser, userId: string, dto: AssignWorkerFacilityDto) {
    const [worker, facility] = await Promise.all([
      this.prisma.user.findUnique({
        where: { id: userId },
        include: { healthWorkerProfile: true },
      }),
      this.prisma.facility.findUnique({ where: { id: dto.facilityId } }),
    ]);
    if (
      !worker ||
      worker.role !== UserRole.HEALTH_WORKER ||
      worker.status !== AccountStatus.ACTIVE ||
      !worker.healthWorkerProfile
    ) {
      throw new NotFoundException('Active health worker profile not found.');
    }
    if (worker.healthWorkerProfile.verificationStatus !== HealthWorkerVerificationStatus.VERIFIED) {
      throw new ForbiddenException('Only verified health workers can be assigned to a facility.');
    }
    if (!facility || facility.status !== FacilityStatus.ACTIVE) {
      throw new NotFoundException('Active facility not found.');
    }

    return this.prisma.$transaction(async (tx) => {
      const updated = await tx.healthWorkerProfile.update({
        where: { userId },
        data: { facilityId: facility.id },
        select: this.profileSelect(),
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: 'HEALTH_WORKER_FACILITY_ASSIGNED',
          entityType: 'HealthWorkerProfile',
          entityId: worker.healthWorkerProfile!.id,
          metadata: { userId, facilityId: facility.id },
        },
      });
      return updated;
    });
  }

  async removeFacilityAssignment(admin: AuthenticatedUser, userId: string) {
    const worker = await this.prisma.healthWorkerProfile.findUnique({ where: { userId } });
    if (!worker) throw new NotFoundException('Health worker profile not found.');
    return this.prisma.$transaction(async (tx) => {
      const updated = await tx.healthWorkerProfile.update({
        where: { userId },
        data: { facilityId: null },
        select: this.profileSelect(),
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: 'HEALTH_WORKER_FACILITY_REMOVED',
          entityType: 'HealthWorkerProfile',
          entityId: worker.id,
          metadata: { userId },
        },
      });
      return updated;
    });
  }

  async listForFacility(facilityId: string) {
    const facility = await this.prisma.facility.findUnique({ where: { id: facilityId }, select: { id: true } });
    if (!facility) throw new NotFoundException('Facility not found.');
    return this.prisma.healthWorkerProfile.findMany({
      where: { facilityId },
      select: {
        id: true,
        designation: true,
        professionalId: true,
        verificationStatus: true,
        verifiedAt: true,
        user: { select: { id: true, displayName: true, phoneNumber: true, status: true } },
      },
      orderBy: { createdAt: 'asc' },
    });
  }

  private async assertHealthWorker(userId: string) {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { id: true, role: true, status: true },
    });
    if (!user || user.status !== AccountStatus.ACTIVE || user.role !== UserRole.HEALTH_WORKER) {
      throw new ForbiddenException('An active health worker account is required.');
    }
  }

  private profileSelect() {
    return {
      id: true,
      userId: true,
      facilityId: true,
      designation: true,
      professionalId: true,
      verificationStatus: true,
      verifiedAt: true,
      createdAt: true,
      updatedAt: true,
      facility: {
        select: {
          id: true,
          name: true,
          facilityType: true,
          address: true,
          locality: true,
          state: true,
          lga: true,
          status: true,
        },
      },
    };
  }
}
