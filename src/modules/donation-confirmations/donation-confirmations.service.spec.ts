import { DonorResponseStatus, EmergencyRequestStatus, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonationConfirmationDto } from './dto/create-donation-confirmation.dto';
import { DonationConfirmationsService } from './donation-confirmations.service';

describe('DonationConfirmationsService', () => {
  it('records one accepted donation and transitions the request to partially filled', async () => {
    const user: AuthenticatedUser = {
      id: '00000000-0000-4000-8000-000000000021',
      role: UserRole.HEALTH_WORKER,
      status: 'ACTIVE',
    };
    const requestId = '00000000-0000-4000-8000-000000000022';
    const facilityId = '00000000-0000-4000-8000-000000000026';
    const dto: CreateDonationConfirmationDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000023',
      donorProfileId: '00000000-0000-4000-8000-000000000024',
      receiptHash: 'a'.repeat(64),
    };
    const created = { id: '00000000-0000-4000-8000-000000000025' };
    const request = {
      id: requestId,
      facilityId,
      status: EmergencyRequestStatus.OPEN,
      units: 2,
      expiresAt: null,
    };
    const worker = {
      status: 'ACTIVE',
      role: UserRole.HEALTH_WORKER,
      healthWorkerProfile: {
        facilityId,
        verificationStatus: 'VERIFIED',
        facility: { status: 'ACTIVE' },
      },
    };
    const tx = {
      $queryRaw: jest.fn(),
      user: { findUnique: jest.fn().mockResolvedValue(worker) },
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue(request),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      },
      donationConfirmation: {
        findUnique: jest.fn().mockResolvedValue(null),
        count: jest.fn().mockResolvedValue(0),
        create: jest.fn().mockResolvedValue(created),
      },
      donorResponse: {
        findUnique: jest.fn().mockResolvedValue({
          id: '00000000-0000-4000-8000-000000000027',
          status: DonorResponseStatus.ACCEPTED,
        }),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue(worker) },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(request) },
      donationConfirmation: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;

    await expect(new DonationConfirmationsService(prisma).confirm(user, requestId, dto)).resolves.toBe(created);
    expect(tx.donationConfirmation.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        requestId,
        donorProfileId: dto.donorProfileId,
        facilityId,
        confirmedByUserId: user.id,
      }),
    });
    expect(tx.emergencyRequest.updateMany).toHaveBeenCalledWith(
      expect.objectContaining({
        data: { status: EmergencyRequestStatus.PARTIALLY_FILLED },
      }),
    );
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          eventType: 'PARTIALLY_FILLED',
          fromStatus: EmergencyRequestStatus.OPEN,
          toStatus: EmergencyRequestStatus.PARTIALLY_FILLED,
        }),
      }),
    );
  });

  it('rejects a confirmation for a donor who did not accept', async () => {
    const user: AuthenticatedUser = {
      id: '00000000-0000-4000-8000-000000000031',
      role: UserRole.HEALTH_WORKER,
      status: 'ACTIVE',
    };
    const facilityId = '00000000-0000-4000-8000-000000000032';
    const requestId = '00000000-0000-4000-8000-000000000033';
    const worker = {
      status: 'ACTIVE',
      role: UserRole.HEALTH_WORKER,
      healthWorkerProfile: {
        facilityId,
        verificationStatus: 'VERIFIED',
        facility: { status: 'ACTIVE' },
      },
    };
    const request = {
      id: requestId,
      facilityId,
      status: EmergencyRequestStatus.OPEN,
      units: 1,
      expiresAt: null,
    };
    const tx = {
      $queryRaw: jest.fn(),
      user: { findUnique: jest.fn().mockResolvedValue(worker) },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(request) },
      donationConfirmation: {
        findUnique: jest.fn().mockResolvedValue(null),
        count: jest.fn(),
        create: jest.fn(),
      },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(null) },
      requestActivity: { create: jest.fn() },
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue(worker) },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(request) },
      donationConfirmation: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;

    await expect(
      new DonationConfirmationsService(prisma).confirm(user, requestId, {
        idempotencyKey: '00000000-0000-4000-8000-000000000034',
        donorProfileId: '00000000-0000-4000-8000-000000000035',
        receiptHash: 'b'.repeat(64),
      }),
    ).rejects.toThrow('The donor must have an accepted response before confirmation.');
    expect(tx.donationConfirmation.create).not.toHaveBeenCalled();
  });
});
