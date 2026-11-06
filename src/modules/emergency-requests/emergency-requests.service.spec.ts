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
    const record = { id: '00000000-0000-4000-8000-000000000006', status: EmergencyRequestStatus.OPEN };
    const tx = {
      emergencyRequest: { create: jest.fn().mockResolvedValue(record) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
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
});
