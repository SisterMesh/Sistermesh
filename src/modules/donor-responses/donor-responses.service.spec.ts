import { DonorResponseStatus, EmergencyRequestStatus, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';
import { WithdrawDonorResponseDto } from './dto/withdraw-donor-response.dto';
import { DonorResponsesService } from './donor-responses.service';

describe('DonorResponsesService', () => {
  const user: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000011',
    role: UserRole.DONOR,
    status: 'ACTIVE',
  };
  const requestId = '00000000-0000-4000-8000-000000000014';
  const donorProfile = {
    id: '00000000-0000-4000-8000-000000000015',
    isAvailable: true,
    bloodGroup: 'O_NEGATIVE',
  };

  it('allows multiple accepted donors without changing the request lifecycle', async () => {
    const dto: CreateDonorResponseDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000012',
      status: DonorResponseStatus.ACCEPTED,
    };
    const created = { id: '00000000-0000-4000-8000-000000000013', status: dto.status };
    const tx = {
      $queryRaw: jest.fn(),
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue({
          id: requestId,
          requestedBloodGroup: 'AB_POSITIVE',
          status: EmergencyRequestStatus.OPEN,
          expiresAt: null,
        }),
        updateMany: jest.fn(),
      },
      user: { findUnique: jest.fn().mockResolvedValue({ status: 'ACTIVE', role: UserRole.DONOR, donorProfile }) },
      donorResponse: {
        findUnique: jest.fn().mockResolvedValue(null),
        create: jest.fn().mockResolvedValue(created),
      },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue({ status: 'ACTIVE', role: UserRole.DONOR, donorProfile }) },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;

    const result = await new DonorResponsesService(prisma).respond(user, requestId, dto);

    expect(result).toBe(created);
    expect(tx.donorResponse.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        requestId,
        status: DonorResponseStatus.ACCEPTED,
        submittedStatus: DonorResponseStatus.ACCEPTED,
      }),
    });
    expect(tx.emergencyRequest.updateMany).not.toHaveBeenCalled();
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ eventType: 'DONOR_ACCEPTED', actorUserId: user.id }),
      }),
    );
  });

  it('replays an original response even after it was later withdrawn', async () => {
    const dto: CreateDonorResponseDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000016',
      status: DonorResponseStatus.ACCEPTED,
    };
    const withdrawn = {
      id: '00000000-0000-4000-8000-000000000017',
      donorUserId: user.id,
      requestId,
      status: DonorResponseStatus.WITHDRAWN,
      submittedStatus: DonorResponseStatus.ACCEPTED,
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue({ status: 'ACTIVE', role: UserRole.DONOR, donorProfile }) },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(withdrawn) },
      $transaction: jest.fn(),
    } as unknown as PrismaService;

    await expect(new DonorResponsesService(prisma).respond(user, requestId, dto)).resolves.toBe(withdrawn);
    expect(prisma.$transaction).not.toHaveBeenCalled();
  });

  it('withdraws only an accepted response and records the withdrawal event transactionally', async () => {
    const dto: WithdrawDonorResponseDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000018',
    };
    const accepted = {
      id: '00000000-0000-4000-8000-000000000019',
      donorUserId: user.id,
      requestId,
      status: DonorResponseStatus.ACCEPTED,
      withdrawalIdempotencyKey: null,
    };
    const withdrawn = { ...accepted, status: DonorResponseStatus.WITHDRAWN, withdrawalIdempotencyKey: dto.idempotencyKey };
    const tx = {
      $queryRaw: jest.fn(),
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue({
          id: requestId,
          status: EmergencyRequestStatus.PARTIALLY_FILLED,
          expiresAt: null,
        }),
      },
      donorResponse: {
        findUnique: jest.fn().mockResolvedValue(accepted),
        update: jest.fn().mockResolvedValue(withdrawn),
      },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: user.id,
          status: 'ACTIVE',
          role: UserRole.DONOR,
          donorProfile: { id: donorProfile.id },
        }),
      },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;

    await expect(new DonorResponsesService(prisma).withdraw(user, requestId, dto)).resolves.toBe(withdrawn);
    expect(tx.donorResponse.update).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { id: accepted.id },
        data: expect.objectContaining({
          status: DonorResponseStatus.WITHDRAWN,
          withdrawalIdempotencyKey: dto.idempotencyKey,
        }),
      }),
    );
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ eventType: 'DONOR_WITHDRAWN', actorUserId: user.id }),
      }),
    );
  });
});
