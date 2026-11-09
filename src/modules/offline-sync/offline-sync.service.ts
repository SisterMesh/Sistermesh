import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  Injectable,
  Logger,
  NotFoundException,
} from '@nestjs/common';
import {
  AccountStatus,
  BloodGroup,
  DonorResponseStatus,
  Prisma,
  SyncBatchStatus,
  SyncEventStatus,
  UserRole,
} from '@prisma/client';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { createHash } from 'node:crypto';
import { isUUID } from 'class-validator';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { CreateDonationConfirmationDto } from '../donation-confirmations/dto/create-donation-confirmation.dto';
import { DonationConfirmationsService } from '../donation-confirmations/donation-confirmations.service';
import { CreateDonorResponseDto } from '../donor-responses/dto/create-donor-response.dto';
import { WithdrawDonorResponseDto } from '../donor-responses/dto/withdraw-donor-response.dto';
import { DonorResponsesService } from '../donor-responses/donor-responses.service';
import { DONOR_GROUPS_FOR_RECIPIENT } from '../donors/blood-compatibility';
import { CreateEmergencyRequestDto } from '../emergency-requests/dto/create-emergency-request.dto';
import { ReviewEmergencyRequestDto } from '../emergency-requests/dto/review-emergency-request.dto';
import { UpdateEmergencyRequestDto } from '../emergency-requests/dto/update-emergency-request.dto';
import { UpdateRequestSubmissionDto } from '../emergency-requests/dto/update-request-submission.dto';
import { EmergencyRequestsService } from '../emergency-requests/emergency-requests.service';
import { SyncRequestUpdatePayloadDto } from './dto/sync-request-update-payload.dto';
import { RegisterDeviceDto } from './dto/register-device.dto';
import { UploadSyncBatchDto } from './dto/upload-sync-batch.dto';
import { AckSyncCheckpointDto } from './dto/ack-sync-checkpoint.dto';
import { SyncEventEnvelope, SyncEventResult, SyncEventType } from './sync-event.types';
import { PrismaService } from '../prisma/prisma.service';

const SUPPORTED_EVENT_TYPES = new Set<string>(Object.values(SyncEventType));
const EVENT_FIELDS = new Set([
  'eventId',
  'eventType',
  'entityId',
  'originDeviceId',
  'localCreatedAt',
  'payloadSchemaVersion',
  'correlationId',
  'payload',
]);
const LEASE_MS = 120_000;
const MAX_EVENT_BYTES = 64 * 1024;
const MAX_BATCH_BYTES = 1024 * 1024;

class SyncValidationError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

@Injectable()
export class OfflineSyncService {
  private readonly logger = new Logger(OfflineSyncService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly emergencyRequests: EmergencyRequestsService,
    private readonly donorResponses: DonorResponsesService,
    private readonly donationConfirmations: DonationConfirmationsService,
  ) {}

  async registerDevice(user: AuthenticatedUser, dto: RegisterDeviceDto) {
    await this.assertActiveUser(user.id);
    const existing = await this.prisma.registeredDevice.findUnique({
      where: {
        userId_clientDeviceId: { userId: user.id, clientDeviceId: dto.clientDeviceId },
      },
    });
    if (existing) {
      if (existing.revokedAt) {
        throw new ConflictException('This device has been revoked and cannot be reactivated.');
      }
      return this.deviceView(existing);
    }

    try {
      const device = await this.prisma.$transaction(async (tx) => {
        const registered = await tx.registeredDevice.create({
          data: {
            userId: user.id,
            clientDeviceId: dto.clientDeviceId,
            name: dto.name?.trim() || null,
            publicKey: dto.publicKey?.trim() || null,
          },
        });
        await tx.syncCheckpoint.create({ data: { registeredDeviceId: registered.id } });
        return registered;
      });
      return this.deviceView(device);
    } catch (error) {
      if (!this.isUniqueConstraint(error)) throw error;
      const raced = await this.prisma.registeredDevice.findUnique({
        where: { userId_clientDeviceId: { userId: user.id, clientDeviceId: dto.clientDeviceId } },
      });
      if (!raced) throw error;
      if (raced.revokedAt) throw new ConflictException('This device has been revoked and cannot be reactivated.');
      return this.deviceView(raced);
    }
  }

  async listDevices(user: AuthenticatedUser) {
    await this.assertActiveUser(user.id);
    const devices = await this.prisma.registeredDevice.findMany({
      where: { userId: user.id },
      select: {
        id: true,
        clientDeviceId: true,
        name: true,
        createdAt: true,
        lastSeenAt: true,
        lastSyncAt: true,
        revokedAt: true,
      },
      orderBy: { createdAt: 'asc' },
    });
    return devices.map((device) => ({
      deviceId: device.id,
      clientDeviceId: device.clientDeviceId,
      name: device.name,
      createdAt: device.createdAt,
      lastSeenAt: device.lastSeenAt,
      lastSyncAt: device.lastSyncAt,
      revokedAt: device.revokedAt,
    }));
  }

  async revokeDevice(user: AuthenticatedUser, deviceId: string) {
    await this.assertActiveUser(user.id);
    const existing = await this.prisma.registeredDevice.findFirst({
      where: { id: deviceId, userId: user.id },
    });
    if (!existing) throw new NotFoundException('Registered device not found.');
    if (existing.revokedAt) return this.deviceView(existing);

    const revoked = await this.prisma.registeredDevice.update({
      where: { id: deviceId },
      data: { revokedAt: new Date() },
    });
    return this.deviceView(revoked);
  }

  async uploadBatch(user: AuthenticatedUser, deviceId: string, dto: UploadSyncBatchDto) {
    const device = await this.assertOwnedActiveDevice(user.id, deviceId);
    if (user.role !== UserRole.INDIVIDUAL && user.role !== UserRole.HEALTH_WORKER) {
      throw new ForbiddenException('Only individuals and health workers can upload coordination events.');
    }
    if (!isUUID(dto.clientBatchId) || !Array.isArray(dto.events) || dto.events.length < 1 || dto.events.length > 100) {
      throw new BadRequestException('A batch requires a UUID and between 1 and 100 events.');
    }
    const requestHash = this.hash(dto.events);
    if (Buffer.byteLength(this.canonicalJson(dto.events), 'utf8') > MAX_BATCH_BYTES) {
      throw new BadRequestException('The batch exceeds the maximum supported size.');
    }

    let batch = await this.prisma.syncBatch.findUnique({
      where: {
        registeredDeviceId_clientBatchId: {
          registeredDeviceId: device.id,
          clientBatchId: dto.clientBatchId,
        },
      },
    });
    if (batch) {
      if (!batch.requestHash || batch.requestHash !== requestHash) {
        throw new ConflictException('This batch ID was already used with different event data.');
      }
    } else {
      try {
        batch = await this.prisma.syncBatch.create({
          data: {
            registeredDeviceId: device.id,
            clientBatchId: dto.clientBatchId,
            requestHash,
            status: SyncBatchStatus.PROCESSING,
            receivedCount: dto.events.length,
          },
        });
      } catch (error) {
        if (!this.isUniqueConstraint(error)) throw error;
        batch = await this.prisma.syncBatch.findUnique({
          where: {
            registeredDeviceId_clientBatchId: {
              registeredDeviceId: device.id,
              clientBatchId: dto.clientBatchId,
            },
          },
        });
        if (!batch || !batch.requestHash || batch.requestHash !== requestHash) {
          throw new ConflictException('This batch ID was already used with different event data.');
        }
      }
    }

    await this.prisma.registeredDevice.update({
      where: { id: device.id },
      data: { lastSeenAt: new Date(), lastSyncAt: new Date() },
    });
    const results: SyncEventResult[] = [];
    for (const rawEvent of dto.events) {
      try {
        results.push(await this.processEvent(user, device, batch.id, rawEvent));
      } catch {
        this.logger.error(`Failed processing an event in sync batch ${batch.id}.`);
        results.push({
          eventId:
            this.isRecord(rawEvent) && typeof rawEvent.eventId === 'string' && isUUID(rawEvent.eventId)
              ? rawEvent.eventId
              : null,
          status: SyncEventStatus.RECEIVED,
          errorCode: 'RETRYABLE_PROCESSING_ERROR',
          explanation: 'A temporary processing failure occurred. Retry with the same event and batch IDs.',
        });
      }
    }

    const processedCount = results.filter((result) => result.status === SyncEventStatus.PROCESSED).length;
    const rejectedCount = results.filter(
      (result) => result.status === SyncEventStatus.REJECTED || result.status === SyncEventStatus.CONFLICT,
    ).length;
    const hasReceived = results.some((result) => result.status === SyncEventStatus.RECEIVED);
    const batchStatus = hasReceived
      ? SyncBatchStatus.PROCESSING
      : rejectedCount === 0
        ? SyncBatchStatus.COMPLETED
        : processedCount > 0
          ? SyncBatchStatus.PARTIALLY_COMPLETED
          : SyncBatchStatus.FAILED;
    let updatedBatch = batch;
    if (batch.status === SyncBatchStatus.PROCESSING) {
      const changed = await this.prisma.syncBatch.updateMany({
        where: { id: batch.id, status: SyncBatchStatus.PROCESSING },
        data: {
          status: batchStatus,
          receivedCount: results.length,
          acceptedCount: processedCount,
          rejectedCount,
          completedAt: hasReceived ? null : new Date(),
        },
      });
      updatedBatch = changed.count
        ? await this.prisma.syncBatch.findUniqueOrThrow({ where: { id: batch.id } })
        : await this.prisma.syncBatch.findUniqueOrThrow({ where: { id: batch.id } });
    }
    return {
      batchId: updatedBatch.id,
      clientBatchId: updatedBatch.clientBatchId,
      status: updatedBatch.status,
      receivedCount: updatedBatch.receivedCount,
      processedCount: updatedBatch.acceptedCount,
      rejectedCount: updatedBatch.rejectedCount,
      events: results,
    };
  }

  async getChanges(user: AuthenticatedUser, deviceId: string, requestedLimit?: number) {
    const device = await this.assertOwnedActiveDevice(user.id, deviceId);
    if (
      requestedLimit !== undefined &&
      (!Number.isInteger(requestedLimit) || requestedLimit < 1 || requestedLimit > 100)
    ) {
      throw new BadRequestException('limit must be an integer between 1 and 100.');
    }
    const limit = requestedLimit ?? 50;
    const where = await this.authorizedActivityWhere(user);
    const checkpoint = await this.prisma.syncCheckpoint.findUniqueOrThrow({
      where: { registeredDeviceId: device.id },
    });
    const highWater = await this.prisma.syncCursorState.findUniqueOrThrow({
      where: { id: 1 },
      select: { value: true },
    });
    const activities = await this.prisma.requestActivity.findMany({
      where: {
        syncCursor: { gt: checkpoint.lastCursor, lte: highWater.value },
        request: where,
      },
      select: {
        syncCursor: true,
        eventType: true,
        fromStatus: true,
        toStatus: true,
        occurredAt: true,
        request: {
          select: {
            id: true,
            createdByUserId: true,
            requestedBloodGroup: true,
            urgency: true,
            units: true,
            status: true,
            expiresAt: true,
            isForSelf: true,
            beneficiaryName: true,
            beneficiaryRelationship: true,
            additionalInfo: true,
            reviewReason: true,
            reviewedAt: true,
            createdAt: true,
            updatedAt: true,
            facility: { select: { id: true, name: true, locality: true, state: true } },
          },
        },
      },
      orderBy: { syncCursor: 'asc' },
      take: limit + 1,
    });
    const hasMore = activities.length > limit;
    const page = hasMore ? activities.slice(0, limit) : activities;
    const pendingCursor = hasMore ? page[page.length - 1].syncCursor : highWater.value;
    await this.prisma.syncCheckpoint.update({
      where: { registeredDeviceId: device.id },
      data: { pendingCursor },
    });
    await this.prisma.registeredDevice.update({
      where: { id: device.id },
      data: { lastSeenAt: new Date() },
    });
    return {
      checkpoint: checkpoint.lastCursor.toString(),
      nextCursor: pendingCursor.toString(),
      hasMore,
      changes: page.map((activity) => {
        const request = activity.request;
        const isMine = request.createdByUserId === user.id;
        const canSeePrivateDetails = isMine || user.role === UserRole.HEALTH_WORKER;
        return {
          cursor: activity.syncCursor.toString(),
          eventType: activity.eventType,
          fromStatus: activity.fromStatus,
          toStatus: activity.toStatus,
          occurredAt: activity.occurredAt,
          request: {
            id: request.id,
            isMine,
            requestedBloodGroup: request.requestedBloodGroup,
            urgency: request.urgency,
            units: request.units,
            status: request.status,
            expiresAt: request.expiresAt,
            createdAt: request.createdAt,
            updatedAt: request.updatedAt,
            facility: request.facility,
            ...(canSeePrivateDetails
              ? {
                  isForSelf: request.isForSelf,
                  beneficiaryName: request.beneficiaryName,
                  beneficiaryRelationship: request.beneficiaryRelationship,
                  additionalInfo: request.additionalInfo,
                  reviewReason: request.reviewReason,
                  reviewedAt: request.reviewedAt,
                }
              : {}),
          },
        };
      }),
    };
  }

  async acknowledgeCheckpoint(user: AuthenticatedUser, deviceId: string, dto: AckSyncCheckpointDto) {
    const device = await this.assertOwnedActiveDevice(user.id, deviceId);
    const cursor = BigInt(dto.cursor);
    const checkpoint = await this.prisma.syncCheckpoint.findUniqueOrThrow({
      where: { registeredDeviceId: device.id },
    });
    if (cursor === checkpoint.lastCursor) return { checkpoint: checkpoint.lastCursor.toString() };
    if (checkpoint.pendingCursor === null || checkpoint.pendingCursor !== cursor) {
      throw new ConflictException('A checkpoint can only advance to the exact nextCursor returned by the latest page.');
    }
    const changed = await this.prisma.syncCheckpoint.updateMany({
      where: {
        registeredDeviceId: device.id,
        lastCursor: checkpoint.lastCursor,
        pendingCursor: cursor,
      },
      data: { lastCursor: cursor, pendingCursor: null },
    });
    if (!changed.count) throw new ConflictException('The checkpoint changed before this acknowledgement.');
    await this.prisma.registeredDevice.update({
      where: { id: device.id },
      data: { lastSeenAt: new Date() },
    });
    return { checkpoint: cursor.toString() };
  }

  async listConflicts(user: AuthenticatedUser, deviceId: string, requestedLimit?: number) {
    const device = await this.assertOwnedActiveDevice(user.id, deviceId);
    if (
      requestedLimit !== undefined &&
      (!Number.isInteger(requestedLimit) || requestedLimit < 1 || requestedLimit > 100)
    ) {
      throw new BadRequestException('limit must be an integer between 1 and 100.');
    }
    const limit = requestedLimit ?? 50;
    const [eventConflicts, reusedIds] = await Promise.all([
      this.prisma.syncEvent.findMany({
        where: {
          registeredDeviceId: device.id,
          userId: user.id,
          status: SyncEventStatus.CONFLICT,
        },
        select: {
          eventId: true,
          eventType: true,
          entityId: true,
          payload: true,
          errorCode: true,
          explanation: true,
          receivedAt: true,
          processedAt: true,
        },
        orderBy: [{ processedAt: 'desc' }, { receivedAt: 'desc' }],
        take: limit,
      }),
      this.prisma.syncConflict.findMany({
        where: { registeredDeviceId: device.id, userId: user.id },
        select: {
          eventId: true,
          conflictCode: true,
          explanation: true,
          incomingPayload: true,
          createdAt: true,
        },
        orderBy: { createdAt: 'desc' },
        take: limit,
      }),
    ]);
    return {
      conflicts: [
        ...eventConflicts.map((event) => ({
          eventId: event.eventId,
          eventType: event.eventType,
          entityId: event.entityId,
          payload: event.payload,
          errorCode: event.errorCode,
          explanation: event.explanation,
          occurredAt: event.processedAt ?? event.receivedAt,
        })),
        ...reusedIds.map((conflict) => ({
          eventId: conflict.eventId,
          errorCode: conflict.conflictCode,
          explanation: conflict.explanation,
          payload: conflict.incomingPayload,
          occurredAt: conflict.createdAt,
        })),
      ]
        .sort((left, right) => right.occurredAt.getTime() - left.occurredAt.getTime())
        .slice(0, limit),
    };
  }

  private async processEvent(
    user: AuthenticatedUser,
    device: { id: string; clientDeviceId: string },
    batchId: string,
    raw: unknown,
  ): Promise<SyncEventResult> {
    device = await this.assertOwnedActiveDevice(user.id, device.id);
    const eventId =
      this.isRecord(raw) && typeof raw.eventId === 'string' && isUUID(raw.eventId)
        ? raw.eventId
        : null;
    const payloadHash = this.hash(raw);
    if (this.isRecord(raw) && Buffer.byteLength(this.canonicalJson(raw), 'utf8') > MAX_EVENT_BYTES) {
      return this.persistRejectedEvent(user, device, batchId, raw, eventId, payloadHash, 'EVENT_TOO_LARGE', 'The event exceeds the maximum supported size.');
    }

    if (eventId) {
      const existing = await this.prisma.syncEvent.findUnique({ where: { eventId } });
      if (existing) {
        if (
          existing.payloadHash !== payloadHash ||
          existing.userId !== user.id ||
          existing.registeredDeviceId !== device.id
        ) {
          await this.preserveConflict(existing.id, eventId, device.id, user.id, payloadHash, raw);
          return {
            eventId,
            status: SyncEventStatus.CONFLICT,
            errorCode: 'EVENT_ID_REUSE',
            explanation: 'This event ID was already used with different data or by another owner/device.',
          };
        }
        return this.resumeOrReturn(existing, user, device, batchId, raw);
      }
    }

    let stored;
    try {
      stored = await this.prisma.syncEvent.create({
        data: this.newEventRecord(user, device, batchId, raw, eventId, payloadHash),
      });
    } catch (error) {
      if (!eventId || !this.isUniqueConstraint(error)) throw error;
      const raced = await this.prisma.syncEvent.findUnique({ where: { eventId } });
      if (!raced) throw error;
      if (
        raced.payloadHash !== payloadHash ||
        raced.userId !== user.id ||
        raced.registeredDeviceId !== device.id
      ) {
        await this.preserveConflict(raced.id, eventId, device.id, user.id, payloadHash, raw);
        return {
          eventId,
          status: SyncEventStatus.CONFLICT,
          errorCode: 'EVENT_ID_REUSE',
          explanation: 'This event ID was already used with different data or by another owner/device.',
        };
      }
      return this.resumeOrReturn(raced, user, device, batchId, raw);
    }

    return this.executeClaimedEvent(stored.id, user, device, raw);
  }

  private async resumeOrReturn(
    existing: Prisma.SyncEventGetPayload<object>,
    user: AuthenticatedUser,
    device: { id: string; clientDeviceId: string },
    batchId: string,
    raw: unknown,
  ): Promise<SyncEventResult> {
    if (existing.status !== SyncEventStatus.RECEIVED) return this.eventResult(existing);
    if (existing.leaseUntil && existing.leaseUntil > new Date()) return this.eventResult(existing);
    const leaseUntil = new Date(Date.now() + LEASE_MS);
    const claimed = await this.prisma.syncEvent.updateMany({
      where: {
        id: existing.id,
        status: SyncEventStatus.RECEIVED,
        OR: [{ leaseUntil: null }, { leaseUntil: { lte: new Date() } }],
      },
      data: { leaseUntil, attemptCount: { increment: 1 }, batchId },
    });
    if (!claimed.count) {
      const latest = await this.prisma.syncEvent.findUniqueOrThrow({ where: { id: existing.id } });
      return this.eventResult(latest);
    }
    return this.executeClaimedEvent(existing.id, user, device, raw);
  }

  private async executeClaimedEvent(
    recordId: string,
    user: AuthenticatedUser,
    device: { id: string; clientDeviceId: string },
    raw: unknown,
  ): Promise<SyncEventResult> {
    try {
      const event = this.parseEnvelope(raw, device.clientDeviceId);
      const entityId = await this.dispatchEvent(user, event);
      const updated = await this.prisma.syncEvent.update({
        where: { id: recordId },
        data: {
          status: SyncEventStatus.PROCESSED,
          resultEntityId: entityId,
          errorCode: null,
          explanation: null,
          leaseUntil: null,
          processedAt: new Date(),
        },
      });
      return this.eventResult(updated);
    } catch (error) {
      if (error instanceof SyncValidationError) {
        const updated = await this.prisma.syncEvent.update({
          where: { id: recordId },
          data: {
            status: SyncEventStatus.REJECTED,
            errorCode: error.code,
            explanation: error.message,
            leaseUntil: null,
            processedAt: new Date(),
          },
        });
        return this.eventResult(updated);
      }
      if (error instanceof HttpException) {
        const status = error.getStatus() === 409 ? SyncEventStatus.CONFLICT : SyncEventStatus.REJECTED;
        const details = this.httpErrorDetails(error);
        const updated = await this.prisma.syncEvent.update({
          where: { id: recordId },
          data: {
            status,
            errorCode: details.code,
            explanation: details.explanation,
            leaseUntil: null,
            processedAt: new Date(),
          },
        });
        return this.eventResult(updated);
      }
      this.logger.error(`Unexpected sync event failure for ${recordId}.`);
      const updated = await this.prisma.syncEvent.update({
        where: { id: recordId },
        data: {
          status: SyncEventStatus.RECEIVED,
          errorCode: 'RETRYABLE_PROCESSING_ERROR',
          explanation: 'A temporary processing failure occurred. Retry with the same event ID.',
          leaseUntil: new Date(),
          processedAt: null,
        },
      });
      return this.eventResult(updated);
    }
  }

  private async dispatchEvent(user: AuthenticatedUser, event: SyncEventEnvelope): Promise<string> {
    const entityId = event.entityId ?? '';
    switch (event.eventType) {
      case SyncEventType.REQUEST_CREATE: {
        if (event.entityId) throw new SyncValidationError('INVALID_ENTITY_ID', 'A request creation event must not include a server entity ID.');
        this.assertPayloadKeys(event.payload, [
          'requestedBloodGroup',
          'urgency',
          'units',
          'expiresAt',
          'facilityId',
          'isForSelf',
          'beneficiaryName',
          'beneficiaryRelationship',
          'additionalInfo',
        ]);
        const dto = await this.validatePayload(CreateEmergencyRequestDto, {
          ...event.payload,
          clientRequestId: event.eventId,
        });
        const created = await this.emergencyRequests.create(user, dto);
        return created.id;
      }
      case SyncEventType.REQUEST_UPDATE: {
        this.assertEntityId(event);
        if (user.role === UserRole.INDIVIDUAL) {
          this.assertPayloadKeys(event.payload, [
            'requestedBloodGroup',
            'urgency',
            'units',
            'expiresAt',
            'expectedUpdatedAt',
            'isForSelf',
            'beneficiaryName',
            'beneficiaryRelationship',
            'additionalInfo',
          ]);
          const payload = await this.validatePayload(UpdateRequestSubmissionDto, event.payload);
          const { expectedUpdatedAt, ...updates } = payload;
          const updated = await this.emergencyRequests.updateSubmission(
            user,
            entityId,
            updates,
            new Date(expectedUpdatedAt),
            event.eventId,
          );
          return updated.id;
        }
        this.assertPayloadKeys(event.payload, [
          'requestedBloodGroup',
          'urgency',
          'units',
          'expiresAt',
          'expectedUpdatedAt',
        ]);
        const payload = await this.validatePayload(SyncRequestUpdatePayloadDto, event.payload);
        const { expectedUpdatedAt, ...updates } = payload;
        const updated = await this.emergencyRequests.update(
          user,
          entityId,
          updates as UpdateEmergencyRequestDto,
          new Date(expectedUpdatedAt),
          event.eventId,
        );
        return updated.id;
      }
      case SyncEventType.REQUEST_REVIEW: {
        this.assertEntityId(event);
        this.assertPayloadKeys(event.payload, ['action', 'reason', 'expectedUpdatedAt']);
        const payload = await this.validatePayload(ReviewEmergencyRequestDto, event.payload);
        const updated = await this.emergencyRequests.review(user, entityId, payload, event.eventId);
        return updated.id;
      }
      case SyncEventType.DONOR_RESPONSE_ACCEPT:
      case SyncEventType.DONOR_RESPONSE_DECLINE: {
        this.assertEntityId(event);
        this.assertPayloadKeys(event.payload, []);
        const dto: CreateDonorResponseDto = {
          idempotencyKey: event.eventId,
          status:
            event.eventType === SyncEventType.DONOR_RESPONSE_ACCEPT
              ? DonorResponseStatus.ACCEPTED
              : DonorResponseStatus.DECLINED,
        };
        await this.donorResponses.respond(user, entityId, dto);
        return entityId;
      }
      case SyncEventType.DONOR_WITHDRAW: {
        this.assertEntityId(event);
        this.assertPayloadKeys(event.payload, []);
        const dto: WithdrawDonorResponseDto = { idempotencyKey: event.eventId };
        await this.donorResponses.withdraw(user, entityId, dto);
        return entityId;
      }
      case SyncEventType.REQUEST_CANCEL: {
        this.assertEntityId(event);
        this.assertPayloadKeys(event.payload, []);
        await this.emergencyRequests.cancel(user, entityId);
        return entityId;
      }
      case SyncEventType.DONATION_CONFIRM: {
        this.assertEntityId(event);
        this.assertPayloadKeys(event.payload, ['donorProfileId', 'receiptHash', 'consentedAt']);
        const dto = await this.validatePayload(CreateDonationConfirmationDto, {
          ...event.payload,
          idempotencyKey: event.eventId,
        });
        await this.donationConfirmations.confirm(user, entityId, dto);
        return entityId;
      }
      default:
        throw new SyncValidationError('UNSUPPORTED_EVENT_TYPE', 'This event type is not supported by this API version.');
    }
  }

  private async persistRejectedEvent(
    user: AuthenticatedUser,
    device: { id: string; clientDeviceId: string },
    batchId: string,
    raw: unknown,
    eventId: string | null,
    payloadHash: string,
    code: string,
    explanation: string,
  ): Promise<SyncEventResult> {
    if (eventId) {
      const existing = await this.prisma.syncEvent.findUnique({ where: { eventId } });
      if (existing) {
        if (existing.payloadHash === payloadHash && existing.userId === user.id && existing.registeredDeviceId === device.id) {
          return this.eventResult(existing);
        }
        await this.preserveConflict(existing.id, eventId, device.id, user.id, payloadHash, raw);
        return {
          eventId,
          status: SyncEventStatus.CONFLICT,
          errorCode: 'EVENT_ID_REUSE',
          explanation: 'This event ID was already used with different data or by another owner/device.',
        };
      }
    }
    const event = await this.prisma.syncEvent.create({
      data: {
        ...this.newEventRecord(user, device, batchId, raw, eventId, payloadHash),
        status: SyncEventStatus.REJECTED,
        errorCode: code,
        explanation,
        leaseUntil: null,
        processedAt: new Date(),
      },
    });
    return this.eventResult(event);
  }

  private newEventRecord(
    user: AuthenticatedUser,
    device: { id: string; clientDeviceId: string },
    batchId: string,
    raw: unknown,
    eventId: string | null,
    payloadHash: string,
  ): Prisma.SyncEventUncheckedCreateInput {
    const record = this.isRecord(raw) ? raw : {};
    const localCreatedAt =
      typeof record.localCreatedAt === 'string' && !Number.isNaN(Date.parse(record.localCreatedAt))
        ? new Date(record.localCreatedAt)
        : null;
    const payload = this.isRecord(record.payload) ? (record.payload as Prisma.InputJsonValue) : {};
    return {
      eventId,
      registeredDeviceId: device.id,
      userId: user.id,
      batchId,
      eventType: typeof record.eventType === 'string' ? record.eventType : null,
      entityId: typeof record.entityId === 'string' ? record.entityId : null,
      originDeviceId: typeof record.originDeviceId === 'string' ? record.originDeviceId : null,
      localCreatedAt,
      payloadSchemaVersion: Number.isInteger(record.payloadSchemaVersion)
        ? (record.payloadSchemaVersion as number)
        : null,
      correlationId: typeof record.correlationId === 'string' ? record.correlationId : null,
      payload,
      payloadHash,
      status: SyncEventStatus.RECEIVED,
      leaseUntil: new Date(Date.now() + LEASE_MS),
    };
  }

  private async preserveConflict(
    syncEventId: string,
    eventId: string,
    registeredDeviceId: string,
    userId: string,
    payloadHash: string,
    raw: unknown,
  ) {
    try {
      await this.prisma.syncConflict.create({
        data: {
          syncEventId,
          eventId,
          registeredDeviceId,
          userId,
          payloadHash,
          conflictCode: 'EVENT_ID_REUSE',
          explanation: 'An existing event ID was submitted with different data or ownership.',
          incomingPayload: this.jsonInput(raw),
        },
      });
    } catch (error) {
      if (!this.isUniqueConstraint(error)) throw error;
    }
  }

  private parseEnvelope(raw: unknown, registeredClientDeviceId: string): SyncEventEnvelope {
    if (!this.isRecord(raw)) throw new SyncValidationError('INVALID_EVENT', 'Each event must be a JSON object.');
    const extraFields = Object.keys(raw).filter((key) => !EVENT_FIELDS.has(key));
    if (extraFields.length) {
      throw new SyncValidationError('UNTRUSTED_FIELDS', 'Event fields may not include user, role, or other undeclared properties.');
    }
    if (typeof raw.eventId !== 'string' || !isUUID(raw.eventId)) {
      throw new SyncValidationError('INVALID_EVENT_ID', 'eventId must be a UUID generated by the client and kept stable across retries.');
    }
    if (typeof raw.eventType !== 'string' || !SUPPORTED_EVENT_TYPES.has(raw.eventType)) {
      throw new SyncValidationError('UNSUPPORTED_EVENT_TYPE', 'This event type is not supported by this API version.');
    }
    if (raw.entityId !== undefined && raw.entityId !== null && (typeof raw.entityId !== 'string' || !isUUID(raw.entityId))) {
      throw new SyncValidationError('INVALID_ENTITY_ID', 'entityId must be a UUID when provided.');
    }
    if (typeof raw.originDeviceId !== 'string' || !isUUID(raw.originDeviceId)) {
      throw new SyncValidationError('INVALID_ORIGIN_DEVICE', 'originDeviceId must be a registered stable device UUID.');
    }
    if (raw.originDeviceId.toLowerCase() !== registeredClientDeviceId.toLowerCase()) {
      throw new SyncValidationError('DEVICE_ORIGIN_MISMATCH', 'This endpoint only processes events originated by the authenticated registered device.');
    }
    if (typeof raw.localCreatedAt !== 'string' || Number.isNaN(Date.parse(raw.localCreatedAt))) {
      throw new SyncValidationError('INVALID_LOCAL_TIMESTAMP', 'localCreatedAt must be an ISO 8601 date-time.');
    }
    if (raw.payloadSchemaVersion !== 1) {
      throw new SyncValidationError('UNSUPPORTED_SCHEMA_VERSION', 'Only payload schema version 1 is supported.');
    }
    if (raw.correlationId !== undefined && (typeof raw.correlationId !== 'string' || !isUUID(raw.correlationId))) {
      throw new SyncValidationError('INVALID_CORRELATION_ID', 'correlationId must be a UUID when provided.');
    }
    if (!this.isRecord(raw.payload)) {
      throw new SyncValidationError('INVALID_PAYLOAD', 'payload must be a JSON object.');
    }
    if (raw.eventType === SyncEventType.REQUEST_CREATE && raw.entityId) {
      throw new SyncValidationError('INVALID_ENTITY_ID', 'A request creation event must not include a server entity ID.');
    }
    if (raw.eventType !== SyncEventType.REQUEST_CREATE && !raw.entityId) {
      throw new SyncValidationError('MISSING_ENTITY_ID', 'entityId is required for this event type.');
    }
    return raw as unknown as SyncEventEnvelope;
  }

  private assertEntityId(event: SyncEventEnvelope): asserts event is SyncEventEnvelope & { entityId: string } {
    if (!event.entityId) throw new SyncValidationError('MISSING_ENTITY_ID', 'entityId is required for this event type.');
  }

  private assertPayloadKeys(payload: Record<string, unknown>, allowedKeys: string[]) {
    const allowed = new Set(allowedKeys);
    if (Object.keys(payload).some((key) => !allowed.has(key))) {
      throw new SyncValidationError('UNTRUSTED_FIELDS', 'The event payload contains unsupported fields.');
    }
  }

  private async validatePayload<T extends object>(type: new () => T, payload: Record<string, unknown>): Promise<T> {
    const instance = plainToInstance(type, payload);
    const errors = await validate(instance, { whitelist: true, forbidNonWhitelisted: true });
    if (errors.length) throw new SyncValidationError('INVALID_PAYLOAD', 'The event payload does not match the versioned event contract.');
    return instance;
  }

  private async authorizedActivityWhere(user: AuthenticatedUser): Promise<Prisma.EmergencyRequestWhereInput> {
    const currentUser = await this.assertActiveUser(user.id);
    if (currentUser.role === UserRole.HEALTH_WORKER) {
      const worker = await this.prisma.healthWorkerProfile.findUnique({
        where: { userId: user.id },
        include: { facility: { select: { status: true } } },
      });
      if (
        worker?.verificationStatus !== 'VERIFIED' ||
        !worker.facilityId ||
        worker.facility?.status !== 'ACTIVE'
      ) {
        throw new ForbiddenException('A verified health worker assigned to an active facility is required.');
      }
      return { facilityId: worker.facilityId };
    }
    if (currentUser.role !== UserRole.INDIVIDUAL) {
      throw new ForbiddenException('Only individuals and health workers can download coordination changes.');
    }
    const donor = await this.prisma.donorProfile.findUnique({
      where: { userId: user.id },
      select: { bloodGroup: true },
    });
    const publicRequestFilter: Prisma.EmergencyRequestWhereInput[] = donor?.bloodGroup
      ? [{
          requestedBloodGroup: {
            in: Object.entries(DONOR_GROUPS_FOR_RECIPIENT)
              .filter(([, donorGroups]) => donorGroups.includes(donor.bloodGroup!))
              .map(([recipient]) => recipient as BloodGroup),
          },
          status: { in: ['OPEN', 'PARTIALLY_FILLED'] },
        }]
      : [];
    return {
      OR: [
        { createdByUserId: user.id },
        { donorResponses: { some: { donorUserId: user.id } } },
        ...publicRequestFilter,
      ],
    };
  }

  private async assertActiveUser(userId: string) {
    const current = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { id: true, role: true, status: true },
    });
    if (!current || current.status !== AccountStatus.ACTIVE) {
      throw new ForbiddenException('An active authenticated account is required.');
    }
    return current;
  }

  private async assertOwnedActiveDevice(userId: string, deviceId: string) {
    await this.assertActiveUser(userId);
    const device = await this.prisma.registeredDevice.findFirst({
      where: { id: deviceId, userId },
    });
    if (!device) throw new NotFoundException('Registered device not found.');
    if (device.revokedAt) throw new ForbiddenException('This device has been revoked.');
    return device;
  }

  private deviceView(device: {
    id: string;
    clientDeviceId: string;
    name: string | null;
    createdAt: Date;
    lastSeenAt: Date | null;
    lastSyncAt: Date | null;
    revokedAt: Date | null;
  }) {
    return {
      deviceId: device.id,
      clientDeviceId: device.clientDeviceId,
      name: device.name,
      createdAt: device.createdAt,
      lastSeenAt: device.lastSeenAt,
      lastSyncAt: device.lastSyncAt,
      revokedAt: device.revokedAt,
    };
  }

  private eventResult(event: {
    eventId: string | null;
    status: SyncEventStatus;
    errorCode: string | null;
    explanation: string | null;
    resultEntityId: string | null;
  }): SyncEventResult {
    return {
      eventId: event.eventId,
      status: event.status,
      ...(event.errorCode ? { errorCode: event.errorCode } : {}),
      ...(event.explanation ? { explanation: event.explanation } : {}),
      ...(event.resultEntityId ? { entityId: event.resultEntityId } : {}),
    };
  }

  private httpErrorDetails(error: HttpException) {
    const explanation = error.message || 'The event could not be applied.';
    const lower = explanation.toLowerCase();
    let code = 'BUSINESS_CONFLICT';
    if (error.getStatus() === 404) code = 'ENTITY_NOT_FOUND';
    else if (error.getStatus() === 403) code = 'NOT_AUTHORIZED';
    else if (lower.includes('changed after') || lower.includes('changed before')) code = 'VERSION_CONFLICT';
    else if (lower.includes('expired')) code = 'REQUEST_EXPIRED';
    else if (
      lower.includes('closed') ||
      lower.includes('fulfilled') ||
      lower.includes('cancel') ||
      lower.includes('no longer accepting')
    ) {
      code = 'REQUEST_CLOSED';
    }
    else if (lower.includes('already responded') || lower.includes('already confirmed') || lower.includes('already used')) {
      code = 'DUPLICATE_OPERATION';
    }
    return { code, explanation };
  }

  private jsonInput(value: unknown): Prisma.InputJsonValue {
    if (value === null || value === undefined) return {};
    return value as Prisma.InputJsonValue;
  }

  private hash(value: unknown) {
    return createHash('sha256').update(this.canonicalJson(value)).digest('hex');
  }

  private canonicalJson(value: unknown): string {
    if (Array.isArray(value)) return `[${value.map((item) => this.canonicalJson(item)).join(',')}]`;
    if (this.isRecord(value)) {
      return `{${Object.keys(value)
        .sort()
        .map((key) => `${JSON.stringify(key)}:${this.canonicalJson(value[key])}`)
        .join(',')}}`;
    }
    return JSON.stringify(value) ?? 'null';
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  private isUniqueConstraint(error: unknown): error is Prisma.PrismaClientKnownRequestError {
    return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
  }
}
