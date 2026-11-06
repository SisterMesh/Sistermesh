import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { FacilityStatus, Prisma } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateFacilityDto } from './dto/create-facility.dto';
import { UpdateFacilityDto } from './dto/update-facility.dto';

@Injectable()
export class FacilitiesService {
  constructor(private readonly prisma: PrismaService) {}

  list() {
    return this.prisma.facility.findMany({
      where: { status: FacilityStatus.ACTIVE },
      select: {
        id: true,
        name: true,
        locality: true,
        state: true,
      },
      orderBy: [{ state: 'asc' }, { name: 'asc' }],
    });
  }

  async getPublicById(id: string) {
    const facility = await this.prisma.facility.findFirst({
      where: { id, status: FacilityStatus.ACTIVE },
      select: {
        id: true,
        name: true,
        facilityType: true,
        address: true,
        locality: true,
        state: true,
        lga: true,
      },
    });
    if (!facility) throw new NotFoundException('Active facility not found.');
    return facility;
  }

  listForAdmin() {
    return this.prisma.facility.findMany({
      orderBy: [{ status: 'asc' }, { name: 'asc' }],
    });
  }

  async getForAdmin(id: string) {
    const facility = await this.prisma.facility.findUnique({ where: { id } });
    if (!facility) throw new NotFoundException('Facility not found.');
    return facility;
  }

  async create(admin: AuthenticatedUser, dto: CreateFacilityDto) {
    return this.prisma.$transaction(async (tx) => {
      const facility = await tx.facility.create({
        data: {
          name: dto.name.trim(),
          facilityType: dto.facilityType?.trim() || null,
          address: dto.address?.trim() || null,
          locality: dto.locality?.trim() || null,
          state: dto.state?.trim() || null,
          lga: dto.lga?.trim() || null,
          contactPhone: dto.contactPhone || null,
          status: FacilityStatus.PENDING,
        },
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: 'FACILITY_CREATED',
          entityType: 'Facility',
          entityId: facility.id,
          metadata: { initialStatus: FacilityStatus.PENDING },
        },
      });
      return facility;
    });
  }

  async update(admin: AuthenticatedUser, id: string, dto: UpdateFacilityDto) {
    const data: Prisma.FacilityUpdateInput = {};
    for (const key of [
      'name',
      'facilityType',
      'address',
      'locality',
      'state',
      'lga',
      'contactPhone',
    ] as const) {
      const value = dto[key];
      if (value !== undefined) {
        (data as Record<string, unknown>)[key] =
          typeof value === 'string' ? value.trim() || null : value;
      }
    }
    if (!Object.keys(data).length) {
      throw new ConflictException('Provide at least one facility field to update.');
    }

    return this.prisma.$transaction(async (tx) => {
      const facility = await tx.facility.update({
        where: { id },
        data,
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: 'FACILITY_UPDATED',
          entityType: 'Facility',
          entityId: facility.id,
          metadata: { fields: Object.keys(data) },
        },
      });
      return facility;
    });
  }

  async updateStatus(admin: AuthenticatedUser, id: string, status: FacilityStatus) {
    return this.prisma.$transaction(async (tx) => {
      const previous = await tx.facility.findUnique({ where: { id }, select: { status: true } });
      if (!previous) throw new NotFoundException('Facility not found.');
      const facility = await tx.facility.update({
        where: { id },
        data: { status },
      });
      await tx.auditLog.create({
        data: {
          actorUserId: admin.id,
          action: status === FacilityStatus.ACTIVE ? 'FACILITY_APPROVED' : 'FACILITY_DEACTIVATED',
          entityType: 'Facility',
          entityId: id,
          metadata: { fromStatus: previous.status, toStatus: status },
        },
      });
      return facility;
    });
  }

  async listHealthWorkers(facilityId: string) {
    const facility = await this.prisma.facility.findUnique({
      where: { id: facilityId },
      select: { id: true },
    });
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
}
