import { BloodGroup, EmergencyRequestStatus, RequestUrgency, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateEmergencyRequestDto } from './dto/create-emergency-request.dto';
import { EmergencyRequestsService } from './emergency-requests.service';

describe('EmergencyRequestsService', () => {
  const user: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000001',
    role: UserRole.HEALTH_WORKER,
    status: 'ACTIVE',
  };

  it('returns an existing request for an identical create retry, even after its expiry', async () => {
    const expiresAt = new Date('2025-01-01T00:00:00.000Z');
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: '00000000-0000-4000-8000-000000000002',
      requestedBloodGroup: BloodGroup.O_NEGATIVE,
      urgency: RequestUrgency.CRITICAL,
      units: 2,
      expiresAt: expiresAt.toISOString(),
    };
    const existing = {
      id: '00000000-0000-4000-8000-000000000003',
      createdByUserId: user.id,
      facilityId: '00000000-0000-4000-8000-000000000004',
      requestedBloodGroup: dto.requestedBloodGroup,
      urgency: dto.urgency,
      units: dto.units,
      expiresAt,
      isForSelf: false,
      beneficiaryName: null,
      beneficiaryRelationship: null,
      additionalInfo: null,
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.HEALTH_WORKER,
          healthWorkerProfile: {
            facilityId: existing.facilityId,
            verificationStatus: 'VERIFIED',
            facility: { status: 'ACTIVE' },
          },
        }),
      },
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue(existing),
        create: jest.fn(),
      },
      facility: { findFirst: jest.fn().mockResolvedValue({ id: existing.facilityId }) },
      $transaction: jest.fn(),
    } as unknown as PrismaService;
    const service = new EmergencyRequestsService(prisma);

    await expect(service.create(user, dto)).resolves.toBe(existing);
    expect(prisma.emergencyRequest.create).not.toHaveBeenCalled();
  });

  it('records a new request and its creation activity in the same transaction', async () => {
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: '00000000-0000-4000-8000-000000000005',
      requestedBloodGroup: BloodGroup.A_POSITIVE,
      urgency: RequestUrgency.URGENT,
      units: 1,
    };
    const record = {
      id: '00000000-0000-4000-8000-000000000006',
      status: EmergencyRequestStatus.PENDING_FACILITY_VERIFICATION,
    };
    const tx = {
      emergencyRequest: { create: jest.fn().mockResolvedValue(record) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      syncCursorState: { update: jest.fn().mockResolvedValue({ value: 1n }) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.HEALTH_WORKER,
          healthWorkerProfile: {
            facilityId: '00000000-0000-4000-8000-000000000004',
            verificationStatus: 'VERIFIED',
            facility: { status: 'ACTIVE' },
          },
        }),
      },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(null) },
      facility: { findFirst: jest.fn().mockResolvedValue({ id: '00000000-0000-4000-8000-000000000004' }) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;
    const service = new EmergencyRequestsService(prisma);

    await expect(service.create(user, dto)).resolves.toBe(record);
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ eventType: 'CREATED', requestId: record.id }),
      }),
    );
  });

  it('stores individual beneficiary details privately and begins pending facility verification', async () => {
    const requester: AuthenticatedUser = {
      id: '00000000-0000-4000-8000-000000000007',
      role: UserRole.INDIVIDUAL,
      status: 'ACTIVE',
    };
    const facilityId = '00000000-0000-4000-8000-000000000008';
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: '00000000-0000-4000-8000-000000000009',
      facilityId,
      requestedBloodGroup: BloodGroup.A_POSITIVE,
      urgency: RequestUrgency.URGENT,
      units: 1,
      isForSelf: false,
      beneficiaryName: 'Private beneficiary',
      beneficiaryRelationship: 'Relative',
      additionalInfo: 'Private coordination note',
    };
    const record = {
      id: '00000000-0000-4000-8000-000000000010',
      status: EmergencyRequestStatus.PENDING_FACILITY_VERIFICATION,
    };
    const tx = {
      emergencyRequest: { create: jest.fn().mockResolvedValue(record) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      syncCursorState: { update: jest.fn().mockResolvedValue({ value: 1n }) },
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue({ id: requester.id, status: 'ACTIVE', role: UserRole.INDIVIDUAL }) },
      facility: { findFirst: jest.fn().mockResolvedValue({ id: facilityId }) },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;

    const created = await new EmergencyRequestsService(prisma).create(requester, dto);

    expect(tx.emergencyRequest.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          status: EmergencyRequestStatus.PENDING_FACILITY_VERIFICATION,
          facilityId,
          isForSelf: false,
          beneficiaryName: 'Private beneficiary',
          additionalInfo: 'Private coordination note',
        }),
      }),
    );
  });

  it('approves only a pending request at the verified worker facility', async () => {
    const facilityId = '00000000-0000-4000-8000-000000000011';
    const requestId = '00000000-0000-4000-8000-000000000012';
    const updatedAt = new Date('2026-11-09T10:00:00.000Z');
    const worker: AuthenticatedUser = {
      id: '00000000-0000-4000-8000-000000000013',
      role: UserRole.HEALTH_WORKER,
      status: 'ACTIVE',
    };
    const request = {
      id: requestId,
      facilityId,
      createdByUserId: '00000000-0000-4000-8000-000000000014',
      status: EmergencyRequestStatus.PENDING_FACILITY_VERIFICATION,
      updatedAt,
    };
    const approved = { ...request, status: EmergencyRequestStatus.OPEN };
    const tx = {
      $queryRaw: jest.fn().mockResolvedValue([]),
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue(request),
        update: jest.fn().mockResolvedValue(approved),
      },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      syncCursorState: { update: jest.fn().mockResolvedValue({ value: 2n }) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.HEALTH_WORKER,
          healthWorkerProfile: {
            facilityId,
            verificationStatus: 'VERIFIED',
            facility: { status: 'ACTIVE' },
          },
        }),
      },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;
    const service = new EmergencyRequestsService(prisma);

    await expect(service.review(worker, requestId, {
      action: 'APPROVE',
      expectedUpdatedAt: updatedAt.toISOString(),
    })).resolves.toBe(approved);
    expect(tx.emergencyRequest.update).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { id: requestId },
        data: expect.objectContaining({
          status: EmergencyRequestStatus.OPEN,
          reviewedByUserId: worker.id,
        }),
      }),
    );
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ eventType: 'FACILITY_APPROVED', requestId }),
      }),
    );
  });
});
