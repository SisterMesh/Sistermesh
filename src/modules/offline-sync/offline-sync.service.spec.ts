import { AccountStatus, SyncBatchStatus, SyncEventStatus, UserRole } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { DonorResponsesService } from '../donor-responses/donor-responses.service';
import { EmergencyRequestsService } from '../emergency-requests/emergency-requests.service';
import { OfflineSyncService } from './offline-sync.service';
import { PrismaService } from '../prisma/prisma.service';

type EventRecord = {
  id: string;
  eventId: string | null;
  status: SyncEventStatus;
  resultEntityId: string | null;
  errorCode: string | null;
  explanation: string | null;
  [key: string]: unknown;
};

describe('OfflineSyncService', () => {
  const user: AuthenticatedUser = {
    id: randomUUID(),
    role: UserRole.INDIVIDUAL,
    status: 'ACTIVE',
  };
  const deviceId = randomUUID();
  const clientDeviceId = randomUUID();
  const requestId = randomUUID();
  const device = {
    id: deviceId,
    userId: user.id,
    clientDeviceId,
    name: 'Test device',
    createdAt: new Date(),
    lastSeenAt: null,
    lastSyncAt: null,
    revokedAt: null,
  };

  const makeService = (overrides: Record<string, unknown> = {}) => {
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: user.id,
          role: user.role,
          status: AccountStatus.ACTIVE,
        }),
      },
      registeredDevice: {
        findUnique: jest.fn(),
        findFirst: jest.fn().mockResolvedValue(device),
        update: jest.fn().mockResolvedValue(device),
      },
      healthWorkerProfile: {
        findUnique: jest.fn().mockResolvedValue({
          verificationStatus: 'VERIFIED',
          facilityId: randomUUID(),
          facility: { status: 'ACTIVE' },
        }),
      },
      syncCheckpoint: {
        findUniqueOrThrow: jest.fn().mockResolvedValue({
          lastCursor: 0n,
          pendingCursor: null,
        }),
        update: jest.fn().mockResolvedValue({}),
      },
      syncCursorState: {
        findUniqueOrThrow: jest.fn().mockResolvedValue({ value: 0n }),
      },
      requestActivity: {
        findMany: jest.fn().mockResolvedValue([]),
      },
      syncBatch: {
        findUnique: jest.fn(),
        create: jest.fn(),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
        findUniqueOrThrow: jest.fn(),
      },
      syncEvent: {
        findUnique: jest.fn().mockResolvedValue(null),
        create: jest.fn(),
        update: jest.fn(),
      },
      ...overrides,
    };
    const emergencyRequests = { create: jest.fn().mockResolvedValue({ id: requestId }) };
    const service = new OfflineSyncService(
      prisma as unknown as PrismaService,
      emergencyRequests as unknown as EmergencyRequestsService,
      {} as DonorResponsesService,
    );
    return { service, prisma, emergencyRequests };
  };

  it('returns an existing active device to its authenticated owner', async () => {
    const { service, prisma } = makeService({
      registeredDevice: {
        findUnique: jest.fn().mockResolvedValue(device),
      },
    });

    await expect(service.registerDevice(user, { clientDeviceId })).resolves.toMatchObject({
      deviceId,
      clientDeviceId,
      revokedAt: null,
    });
    expect(prisma.user.findUnique).toHaveBeenCalledWith({
      where: { id: user.id },
      select: { id: true, role: true, status: true },
    });
  });

  it('processes valid siblings and retains invalid events in a partial batch result', async () => {
    const { service, prisma, emergencyRequests } = makeService();
    const batchId = randomUUID();
    const firstEventId = randomUUID();
    const secondEventId = randomUUID();
    const now = new Date().toISOString();
    const records = new Map<string, EventRecord>();
    const eventRecord = (data: Record<string, unknown>): EventRecord => ({
      ...data,
      id: randomUUID(),
      status: SyncEventStatus.RECEIVED,
      resultEntityId: null,
      errorCode: null,
      explanation: null,
    }) as EventRecord;
    (prisma.syncBatch.create as jest.Mock).mockResolvedValue({
      id: batchId,
      clientBatchId: batchId,
      status: SyncBatchStatus.PROCESSING,
    });
    (prisma.syncEvent.create as jest.Mock).mockImplementation(
      ({ data }: { data: Record<string, unknown> }) => {
        const record = eventRecord(data);
        records.set(record.id, record);
        return Promise.resolve(record);
      },
    );
    (prisma.syncEvent.update as jest.Mock).mockImplementation(
      ({ where, data }: { where: { id: string }; data: Record<string, unknown> }) => {
        const current = records.get(where.id);
        if (!current) throw new Error(`Missing event record ${where.id}`);
        const record = { ...current, ...data } as EventRecord;
        records.set(where.id, record);
        return Promise.resolve(record);
      },
    );
    (prisma.syncBatch.findUniqueOrThrow as jest.Mock).mockResolvedValue({
      id: batchId,
      clientBatchId: batchId,
      status: SyncBatchStatus.PARTIALLY_COMPLETED,
      receivedCount: 2,
      acceptedCount: 1,
      rejectedCount: 1,
    });

    const result = await service.uploadBatch(user, deviceId, {
      clientBatchId: batchId,
      events: [
        {
          eventId: firstEventId,
          eventType: 'request.create',
          entityId: null,
          originDeviceId: clientDeviceId,
          localCreatedAt: now,
          payloadSchemaVersion: 1,
          payload: { requestedBloodGroup: 'O_NEGATIVE', urgency: 'URGENT', units: 1 },
        },
        {
          eventId: secondEventId,
          eventType: 'request.create',
          entityId: null,
          originDeviceId: clientDeviceId,
          localCreatedAt: now,
          payloadSchemaVersion: 1,
          payload: { requestedBloodGroup: 'O_NEGATIVE', urgency: 'URGENT', units: 1 },
          userId: 'untrusted',
        },
      ],
    });

    expect(result.events.map((event) => event.status)).toEqual([
      SyncEventStatus.PROCESSED,
      SyncEventStatus.REJECTED,
    ]);
    expect(result.events[1].errorCode).toBe('UNTRUSTED_FIELDS');
    expect(emergencyRequests.create).toHaveBeenCalledTimes(1);
    expect(emergencyRequests.create).toHaveBeenCalledWith(
      expect.objectContaining({ id: user.id }),
      expect.objectContaining({ clientRequestId: firstEventId }),
    );
  });

  it('captures and bounds downloads to a high-water cursor before reading the page', async () => {
    const callOrder: string[] = [];
    const highWater = 25n;
    const { service, prisma } = makeService({
      syncCursorState: {
        findUniqueOrThrow: jest.fn().mockImplementation(async () => {
          callOrder.push('high-water');
          return { value: highWater };
        }),
      },
      requestActivity: {
        findMany: jest.fn().mockImplementation(async (args: { where: { syncCursor: { lte: bigint } } }) => {
          callOrder.push('activity-page');
          expect(args.where.syncCursor.lte).toBe(highWater);
          return [];
        }),
      },
    });

    const page = await service.getChanges(user, deviceId, 10);
    expect(callOrder).toEqual(['high-water', 'activity-page']);
    expect(page).toMatchObject({ checkpoint: '0', nextCursor: '25', hasMore: false, changes: [] });
    expect(prisma.syncCheckpoint.update).toHaveBeenCalledWith({
      where: { registeredDeviceId: deviceId },
      data: { pendingCursor: highWater },
    });
  });
});
