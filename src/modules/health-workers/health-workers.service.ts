import {
  ConflictException,
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
import { PrismaService } from '../prisma/prisma.service';
import { AssignFacilityDto } from './dto/assign-facility.dto';
import { SetVerificationStatusDto } from './dto/set-verification-status.dto';
import { UpdateHealthWorkerProfileDto } from './dto/update-health-worker-profile.dto';

const profileSelect = {
  id: true,
  userId: true,
  facilityId: true,
  designation: true,
  professionalIdentifier: true,
  verificationStatus: true,
  createdAt: true,
  updatedAt: true,
  facility: {
    select: {
      id: true,
      name: true,
      facilityType: true,
      locality: true,
      localGovernmentArea: true,
      state: true,
      status: true,
    },
  },
} satisfies Prisma.HealthWorkerProfileSelect;

@Injectable()
export class HealthWorkersService {
  constructor(private readonly prisma: PrismaService) {}

  async getOwnProfile(userId: string) {
    const profile = await this.prisma.healthWorkerProfile.findUnique({
      where: { userId },
      select: profileSelect,
    });
    if (!profile) throw new NotFoundException('Health-worker profile not found.');
    return profile;
  }

  async updateOwnProfile(
    userId: string,
    dto: UpdateHealthWorkerProfileDto,
  ) {
    const existing = await this.prisma.healthWorkerProfile.findUnique({
      where: { userId },
      select: { professionalIdentifier: true },
    });
    const identifierChanged =
      dto.professionalIdentifier !== undefined &&
      dto.professionalIdentifier !== existing?.professionalIdentifier;
    try {
      return await this.prisma.healthWorkerProfile.upsert({
        where: { userId },
        create: {
          userId,
          ...dto,
          ...(dto.professionalIdentifier !== undefined && {
            verificationStatus: HealthWorkerVerificationStatus.PENDING,
          }),
        },
        update: {
          ...dto,
          ...(identifierChanged && {
            verificationStatus: HealthWorkerVerificationStatus.PENDING,
          }),
        },
        select: profileSelect,
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('That professional identifier is already in use.');
      }
      throw error;
    }
  }

  async setVerificationStatus(
    userId: string,
    status: SetVerificationStatusDto['status'],
  ) {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { role: true },
    });
    if (!user || user.role !== UserRole.HEALTH_WORKER) {
      throw new NotFoundException('Health-worker account not found.');
    }
    try {
      return await this.prisma.healthWorkerProfile.update({
        where: { userId },
        data: { verificationStatus: status },
        select: profileSelect,
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2025'
      ) {
        throw new NotFoundException('Health-worker profile not found.');
      }
      throw error;
    }
  }

  async assignFacility(userId: string, dto: AssignFacilityDto) {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { role: true },
    });
    if (!user || user.role !== UserRole.HEALTH_WORKER) {
      throw new NotFoundException('Health-worker account not found.');
    }
    if (dto.facilityId) {
      const facility = await this.prisma.facility.findUnique({
        where: { id: dto.facilityId },
        select: { status: true },
      });
      if (!facility || facility.status !== FacilityStatus.ACTIVE) {
        throw new ForbiddenException('Health workers may only be assigned to active facilities.');
      }
    }
    try {
      return await this.prisma.healthWorkerProfile.upsert({
        where: { userId },
        create: { userId, facilityId: dto.facilityId ?? null },
        update: { facilityId: dto.facilityId ?? null },
        select: profileSelect,
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2025'
      ) {
        throw new NotFoundException('Health-worker profile not found.');
      }
      throw error;
    }
  }
}
