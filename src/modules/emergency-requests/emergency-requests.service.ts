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
import { ReviewEmergencyRequestDto } from './dto/review-emergency-request.dto';
import { UpdateEmergencyRequestDto } from './dto/update-emergency-request.dto';
import { UpdateRequestSubmissionDto } from './dto/update-request-submission.dto';
import { createSyncActivity } from '../offline-sync/sync-cursor';

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
  }

  onModuleDestroy() {
    if (this.expiryTimer) clearInterval(this.expiryTimer);
  }

  async create(user: AuthenticatedUser, dto: CreateEmergencyRequestDto) {
    const actor = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, role: true, status: true },
    });
    if (!actor || actor.status !== 'ACTIVE' || actor.role !== UserRole.INDIVIDUAL) {
      throw new ForbiddenException('An active individual account is required.');
    }
    const selectedFacility = dto.facilityId
      ? await this.prisma.facility.findFirst({
          where: { id: dto.facilityId },
          select: { id: true, name: true, locality: true, state: true },
        })
      : null;
    if (dto.facilityId && !selectedFacility) {
      throw new BadRequestException('The selected facility is not available.');
    }
    const facilityName = dto.facilityName?.trim() || selectedFacility?.name || '';
    const facilityLocation = dto.facilityLocation?.trim() ||
      [selectedFacility?.locality, selectedFacility?.state].filter(Boolean).join(', ');
    if (!facilityName || !facilityLocation) {
      throw new BadRequestException('Enter the donation facility name and location.');
    }
    const facilityLatitude = dto.facilityLatitude ?? null;
    const facilityLongitude = dto.facilityLongitude ?? null;
    if ((facilityLatitude === null) !== (facilityLongitude === null)) {
      throw new BadRequestException('Enter both facility coordinates or neither.');
    }
    const isForSelf = dto.isForSelf ?? true;
    const beneficiaryName = dto.beneficiaryName?.trim() || null;
    const beneficiaryRelationship = dto.beneficiaryRelationship?.trim() || null;
    const additionalInfo = dto.additionalInfo?.trim() || null;
    if (isForSelf && (beneficiaryName || beneficiaryRelationship)) {
      throw new BadRequestException('Beneficiary details must be omitted for a request for yourself.');
    }
    if (!isForSelf && !beneficiaryName) {
      throw new BadRequestException('Enter the patient or beneficiary name for a request on someone else’s behalf.');
    }

    const expiresAt = dto.expiresAt ? new Date(dto.expiresAt) : null;
    const existing = await this.prisma.emergencyRequest.findUnique({
      where: { clientRequestId: dto.clientRequestId },
      include: { facility: true },
    });
    if (existing) {
      return this.assertSameCreate(existing, user.id, facilityName, facilityLocation,
        facilityLatitude, facilityLongitude, dto, expiresAt, isForSelf,
        beneficiaryName, beneficiaryRelationship, additionalInfo);
    }
    if (expiresAt && expiresAt <= new Date()) {
      throw new BadRequestException('Request expiry must be in the future.');
    }

    try {
      return await this.prisma.$transaction(async (tx) => {
        const request = await tx.emergencyRequest.create({
          data: {
            clientRequestId: dto.clientRequestId,
            createdByUserId: user.id,
            facilityId: selectedFacility?.id ?? null,
            facilityName,
            facilityLocation,
            facilityLatitude,
            facilityLongitude,
            isForSelf,
            beneficiaryName,
            beneficiaryRelationship,
            additionalInfo,
            requestedBloodGroup: dto.requestedBloodGroup,
            urgency: dto.urgency,
            units: dto.units,
            expiresAt,
            status: EmergencyRequestStatus.OPEN,
          },
          include: { facility: true },
        });
        await createSyncActivity(tx, {
            requestId: request.id,
            actorUserId: user.id,
            eventType: RequestActivityType.CREATED,
            toStatus: request.status,
            metadata: { facilityName, facilityLocation, units: dto.units, isForSelf },
        });
        return request;
      });
    } catch (error) {
      if (this.isUniqueConstraint(error)) {
        const duplicate = await this.prisma.emergencyRequest.findUnique({
          where: { clientRequestId: dto.clientRequestId },
          include: { facility: true },
        });
        if (duplicate) {
          return this.assertSameCreate(duplicate, user.id, facilityName, facilityLocation,
            facilityLatitude, facilityLongitude, dto, expiresAt, isForSelf,
            beneficiaryName, beneficiaryRelationship, additionalInfo);
        }
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
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.INDIVIDUAL || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    const donorBloodGroup = donor.donorProfile.bloodGroup;
    if (!donor.donorProfile.isAvailable || !donorBloodGroup) return [];

    const now = new Date();
    const requests = await this.prisma.emergencyRequest.findMany({
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
        facilityId: true,
        facilityName: true,
        facilityLocation: true,
        facilityLatitude: true,
        facilityLongitude: true,
        facility: { select: { id: true, name: true, locality: true, state: true } },
      },
      orderBy: [{ urgency: 'desc' }, { createdAt: 'desc' }],
    });
    return requests.map((request) => ({
      ...request,
      facility: request.facility ?? {
        id: request.facilityId ?? '',
        name: request.facilityName,
        locality: request.facilityLocation,
        state: '',
      },
    }));
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

  async listMine(user: AuthenticatedUser) {
    const current = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, status: true, role: true },
    });
    if (!current || current.status !== 'ACTIVE' || current.role !== UserRole.INDIVIDUAL) {
      throw new ForbiddenException('An active individual account is required.');
    }
    return this.prisma.emergencyRequest.findMany({
      where: { createdByUserId: user.id },
      include: { facility: { select: { id: true, name: true, locality: true, state: true } } },
      orderBy: { createdAt: 'desc' },
    });
  }

  async updateSubmission(
    user: AuthenticatedUser,
    requestId: string,
    dto: Omit<UpdateRequestSubmissionDto, 'expectedUpdatedAt'>,
    expectedUpdatedAt: Date,
    syncEventId?: string,
  ) {
    const currentUser = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, status: true, role: true },
    });
    if (!currentUser || currentUser.status !== 'ACTIVE' || currentUser.role !== UserRole.INDIVIDUAL) {
      throw new ForbiddenException('An active individual account is required.');
    }
    return this.prisma.$transaction(async (tx) => {
      const request = await this.lockRequest(tx, requestId);
      if (!request || request.createdByUserId !== user.id) {
        throw new NotFoundException('Your request was not found.');
      }
      if (syncEventId) {
        const prior = await tx.requestActivity.findUnique({
          where: { syncEventId },
          select: { requestId: true },
        });
        if (prior) {
          if (prior.requestId !== requestId) throw new ConflictException('This event ID was used for another request.');
          return tx.emergencyRequest.findUniqueOrThrow({ where: { id: requestId }, include: { facility: true } });
        }
      }
      if (request.updatedAt.getTime() !== expectedUpdatedAt.getTime()) {
        throw new ConflictException('The request changed after the device last synchronized.');
      }
      if (!this.isOpen(request.status)) {
        throw new ConflictException('Only an open request can be changed.');
      }
      const isForSelf = dto.isForSelf ?? request.isForSelf;
      const beneficiaryName = dto.beneficiaryName === undefined
        ? request.beneficiaryName
        : dto.beneficiaryName.trim() || null;
      const beneficiaryRelationship = dto.beneficiaryRelationship === undefined
        ? request.beneficiaryRelationship
        : dto.beneficiaryRelationship.trim() || null;
      if (isForSelf && (beneficiaryName || beneficiaryRelationship)) {
        throw new BadRequestException('Beneficiary details must be omitted for a request for yourself.');
      }
      if (!isForSelf && !beneficiaryName) {
        throw new BadRequestException('Enter the patient or beneficiary name.');
      }
      const data: Prisma.EmergencyRequestUpdateInput = {
        isForSelf,
        beneficiaryName,
        beneficiaryRelationship,
        ...(dto.additionalInfo !== undefined ? { additionalInfo: dto.additionalInfo.trim() || null } : {}),
        ...(dto.requestedBloodGroup !== undefined ? { requestedBloodGroup: dto.requestedBloodGroup } : {}),
        ...(dto.urgency !== undefined ? { urgency: dto.urgency } : {}),
        ...(dto.units !== undefined ? { units: dto.units } : {}),
        ...(dto.expiresAt !== undefined
          ? { expiresAt: dto.expiresAt ? new Date(dto.expiresAt) : null }
          : {}),
      };
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data,
        include: { facility: true },
      });
      await createSyncActivity(tx, {
        requestId,
        actorUserId: user.id,
        eventType: RequestActivityType.UPDATED,
        syncEventId: syncEventId ?? null,
        fromStatus: request.status,
        toStatus: updated.status,
        metadata: { fields: Object.keys(dto) },
      });
      return updated;
    });
  }

  async review(
    user: AuthenticatedUser,
    requestId: string,
    dto: ReviewEmergencyRequestDto,
    syncEventId?: string,
  ) {
    const facilityId = await this.getWorkerFacility(user);
    const reason = dto.reason?.trim() || null;
    if (dto.action !== 'APPROVE' && !reason) {
      throw new BadRequestException('A reason is required when rejecting or requesting information.');
    }
    return this.prisma.$transaction(async (tx) => {
      const request = await this.lockRequest(tx, requestId);
      if (!request || request.facilityId !== facilityId) {
        throw new NotFoundException('Emergency request not found.');
      }
      if (syncEventId) {
        const prior = await tx.requestActivity.findUnique({
          where: { syncEventId },
          select: { requestId: true },
        });
        if (prior) {
          if (prior.requestId !== requestId) throw new ConflictException('This event ID was used for another request.');
          return tx.emergencyRequest.findUniqueOrThrow({ where: { id: requestId }, include: { facility: true } });
        }
      }
      if (request.updatedAt.getTime() !== new Date(dto.expectedUpdatedAt).getTime()) {
        throw new ConflictException('The request changed after the device last synchronized.');
      }
      if (
        request.status !== EmergencyRequestStatus.PENDING_FACILITY_VERIFICATION &&
        request.status !== EmergencyRequestStatus.NEEDS_INFORMATION
      ) {
        throw new ConflictException('Only requests awaiting facility verification can be reviewed.');
      }
      const nextStatus = dto.action === 'APPROVE'
        ? EmergencyRequestStatus.OPEN
        : dto.action === 'REJECT'
          ? EmergencyRequestStatus.REJECTED
          : EmergencyRequestStatus.NEEDS_INFORMATION;
      const eventType = dto.action === 'APPROVE'
        ? RequestActivityType.FACILITY_APPROVED
        : dto.action === 'REJECT'
          ? RequestActivityType.FACILITY_REJECTED
          : RequestActivityType.ADDITIONAL_INFO_REQUESTED;
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data: {
          status: nextStatus,
          reviewReason: reason,
          reviewedByUserId: user.id,
          reviewedAt: new Date(),
        },
        include: { facility: true },
      });
      await createSyncActivity(tx, {
        requestId,
        actorUserId: user.id,
        eventType,
        syncEventId: syncEventId ?? null,
        fromStatus: request.status,
        toStatus: nextStatus,
        metadata: reason ? { reason } : undefined,
      });
      return updated;
    });
  }

  async update(
    user: AuthenticatedUser,
    requestId: string,
    dto: UpdateEmergencyRequestDto,
    expectedUpdatedAt?: Date,
    syncEventId?: string,
  ) {
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
      if (syncEventId) {
        const priorSyncActivity = await tx.requestActivity.findUnique({
          where: { syncEventId },
          select: { requestId: true },
        });
        if (priorSyncActivity) {
          if (priorSyncActivity.requestId !== requestId) {
            throw new ConflictException('This event ID was already used to update a different request.');
          }
          return {
            request: await tx.emergencyRequest.findUniqueOrThrow({
              where: { id: requestId },
              include: { facility: true },
            }),
          };
        }
      }
      if (expectedUpdatedAt && request.updatedAt.getTime() !== expectedUpdatedAt.getTime()) {
        throw new ConflictException('The request changed after the device last synchronized.');
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
      if (!Object.keys(data).length) {
        throw new BadRequestException('Provide at least one request field to update.');
      }
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data,
        include: { facility: true },
      });
      await createSyncActivity(tx, {
          requestId,
          actorUserId: user.id,
          eventType: RequestActivityType.UPDATED,
          syncEventId: syncEventId ?? null,
          fromStatus: request.status,
          toStatus: request.status,
          metadata: { fields: Object.keys(data) },
      });
      if (confirmedCount > 0 && confirmedCount >= updated.units) {
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
    const actor = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, status: true, role: true },
    });
    if (!actor || actor.status !== 'ACTIVE') throw new ForbiddenException('An active account is required.');
    if (actor.role !== UserRole.INDIVIDUAL) {
      throw new ForbiddenException('Only the requester can cancel this request.');
    }
    const result = await this.prisma.$transaction(async (tx) => {
      const request = await this.lockRequest(tx, requestId);
      const authorized = request?.createdByUserId === user.id;
      if (!request || !authorized) {
        throw new NotFoundException('Emergency request not found.');
      }
      if (request.status === EmergencyRequestStatus.CANCELLED) {
        return { request: await tx.emergencyRequest.findUnique({ where: { id: requestId }, include: { facility: true } }) };
      }
      if (await this.expireLockedRequest(tx, request, new Date())) return { expired: true as const };
      if (
        request.status === EmergencyRequestStatus.FULFILLED ||
        request.status === EmergencyRequestStatus.EXPIRED
      ) {
        throw new ConflictException(`Cannot cancel a request in ${request.status} status.`);
      }
      const updated = await tx.emergencyRequest.update({
        where: { id: requestId },
        data: { status: EmergencyRequestStatus.CANCELLED },
        include: { facility: true },
      });
      await createSyncActivity(tx, {
        requestId,
        actorUserId: user.id,
        eventType: RequestActivityType.CANCELLED,
        fromStatus: request.status,
        toStatus: EmergencyRequestStatus.CANCELLED,
      });
      return { request: updated };
    });
    if ('expired' in result) throw new ConflictException('This emergency request has expired.');
    return result.request;
  }

  async history(user: AuthenticatedUser, requestId: string) {
    await this.expireDueRequests();
    const facilityId = await this.getWorkerFacility(user);
    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId }, select: { facilityId: true } });
    if (!request || request.facilityId !== facilityId) {
      throw new NotFoundException('Emergency request not found.');
    }
    return this.prisma.requestActivity.findMany({
      where: { requestId },
      select: {
        id: true,
        actorUserId: true,
        eventType: true,
        fromStatus: true,
        toStatus: true,
        occurredAt: true,
        metadata: true,
      },
      orderBy: { occurredAt: 'asc' },
    });
  }

  async expireDueRequests(): Promise<number> {
    const due = await this.prisma.emergencyRequest.findMany({
      where: {
        status: { in: [EmergencyRequestStatus.OPEN, EmergencyRequestStatus.PARTIALLY_FILLED] },
        expiresAt: { lte: new Date() },
      },
      select: { id: true },
    });
    let expiredCount = 0;
    for (const { id } of due) {
      const expired = await this.prisma.$transaction(async (tx) => {
        const request = await this.lockRequest(tx, id);
        if (!request) return false;
        return this.expireLockedRequest(tx, request, new Date());
      });
      if (expired) expiredCount += 1;
    }
    return expiredCount;
  }

  private async lockRequest(tx: Prisma.TransactionClient, requestId: string) {
    await tx.$queryRaw`SELECT "id" FROM "EmergencyRequest" WHERE "id" = ${requestId}::uuid FOR UPDATE`;
    return tx.emergencyRequest.findUnique({ where: { id: requestId } });
  }

  private async getWorkerFacility(_user: AuthenticatedUser): Promise<string> {
    throw new ForbiddenException('Health-worker facility workflows are no longer available.');
  }

  private isOpen(status: EmergencyRequestStatus) {
    return status === EmergencyRequestStatus.OPEN || status === EmergencyRequestStatus.PARTIALLY_FILLED;
  }

  private async expireLockedRequest(
    tx: Prisma.TransactionClient,
    request: { id: string; status: EmergencyRequestStatus; expiresAt: Date | null },
    now: Date,
  ) {
    if (!this.isOpen(request.status) || !request.expiresAt || request.expiresAt > now) return false;
    const changed = await tx.emergencyRequest.updateMany({
      where: {
        id: request.id,
        status: request.status,
        expiresAt: { lte: now },
      },
      data: { status: EmergencyRequestStatus.EXPIRED },
    });
    if (!changed.count) return false;
    await createSyncActivity(tx, {
        requestId: request.id,
        eventType: RequestActivityType.EXPIRED,
        fromStatus: request.status,
        toStatus: EmergencyRequestStatus.EXPIRED,
    });
    return true;
  }

  private async recordStatusTransition(
    tx: Prisma.TransactionClient,
    requestId: string,
    actorUserId: string | null,
    fromStatus: EmergencyRequestStatus,
    toStatus: EmergencyRequestStatus,
  ) {
    const eventType =
      toStatus === EmergencyRequestStatus.PARTIALLY_FILLED
        ? RequestActivityType.PARTIALLY_FILLED
        : toStatus === EmergencyRequestStatus.FULFILLED
          ? RequestActivityType.FULFILLED
          : RequestActivityType.STATUS_CHANGED;
    await createSyncActivity(tx, { requestId, actorUserId, eventType, fromStatus, toStatus });
  }

  private assertSameCreate(
    existing: {
      id: string;
      createdByUserId: string;
      facilityId: string | null;
      facilityName: string;
      facilityLocation: string;
      facilityLatitude: number | null;
      facilityLongitude: number | null;
      requestedBloodGroup: BloodGroup;
      urgency: string;
      units: number;
      expiresAt: Date | null;
      isForSelf: boolean;
      beneficiaryName: string | null;
      beneficiaryRelationship: string | null;
      additionalInfo: string | null;
    },
    userId: string,
    facilityName: string,
    facilityLocation: string,
    facilityLatitude: number | null,
    facilityLongitude: number | null,
    dto: CreateEmergencyRequestDto,
    expiresAt: Date | null,
    isForSelf: boolean,
    beneficiaryName: string | null,
    beneficiaryRelationship: string | null,
    additionalInfo: string | null,
  ) {
    const same =
      existing.createdByUserId === userId &&
      existing.facilityName === facilityName &&
      existing.facilityLocation === facilityLocation &&
      existing.facilityLatitude === facilityLatitude &&
      existing.facilityLongitude === facilityLongitude &&
      existing.requestedBloodGroup === dto.requestedBloodGroup &&
      existing.urgency === dto.urgency &&
      existing.units === dto.units &&
      existing.isForSelf === isForSelf &&
      existing.beneficiaryName === beneficiaryName &&
      existing.beneficiaryRelationship === beneficiaryRelationship &&
      existing.additionalInfo === additionalInfo &&
      (existing.expiresAt?.getTime() ?? null) === (expiresAt?.getTime() ?? null);
    if (!same) throw new ConflictException('This client request ID was already used for different request data.');
    return existing;
  }

  private isUniqueConstraint(error: unknown): error is Prisma.PrismaClientKnownRequestError {
    return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
  }
}
