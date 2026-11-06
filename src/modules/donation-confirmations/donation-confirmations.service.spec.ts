import { DonorResponseStatus, EmergencyRequestStatus, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonationConfirmationDto } from './dto/create-donation-confirmation.dto';
import { DonationConfirmationsService } from './donation-confirmations.service';

describe('DonationConfirmationsService', () => {
  it('confirms an accepted donor, completes the request, and records the activity', async () => {
    const user: AuthenticatedUser = {
      id: '00000000-0000-4000-8000-000000000021',
      role: UserRole.HEALTH_WORKER,
      status: 'ACTIVE',
    };
    const requestId = '00000000-0000-4000-8000-000000000022';
    const dto: CreateDonationConfirmationDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000023',
      donorProfileId: '00000000-0000-4000-8000-000000000024',
      receiptHash: 'a'.repeat(64),
    };
    const created = { id: '00000000-0000-4000-8000-000000000025' };
    const tx = {
      donationConfirmation: { create: jest.fn().mockResolvedValue(created) },
      donorResponse: { update: jest.fn().mockResolvedValue({}) },
      emergencyRequest: { updateMany: jest.fn().mockResolvedValue({ count: 1 }) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.HEALTH_WORKER,
          healthWorkerProfile: {
            facilityId: '00000000-0000-4000-8000-000000000026',
            verificationStatus: 'VERIFIED',
            facility: { status: 'ACTIVE' },
          },
        }),
      },
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue({
          id: requestId,
          facilityId: '00000000-0000-4000-8000-000000000026',
          status: EmergencyRequestStatus.ACCEPTED,
          units: 2,
        }),
      },
      donationConfirmation: { findUnique: jest.fn().mockResolvedValue(null) },
      donorResponse: {
        findUnique: jest.fn().mockResolvedValue({
          id: '00000000-0000-4000-8000-000000000027',
          status: DonorResponseStatus.ACCEPTED,
        }),
      },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;
    const service = new DonationConfirmationsService(prisma);

    await expect(service.confirm(user, requestId, dto)).resolves.toBe(created);
    expect(tx.emergencyRequest.updateMany).toHaveBeenCalledWith(
      expect.objectContaining({
        data: { status: EmergencyRequestStatus.COMPLETED },
      }),
    );
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          eventType: 'DONATION_CONFIRMED',
          fromStatus: EmergencyRequestStatus.ACCEPTED,
          toStatus: EmergencyRequestStatus.COMPLETED,
        }),
      }),
    );
  });
});
