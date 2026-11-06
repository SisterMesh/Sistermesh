import { DonorResponseStatus, EmergencyRequestStatus, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';
import { DonorResponsesService } from './donor-responses.service';

describe('DonorResponsesService', () => {
  const user: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000011',
    role: UserRole.DONOR,
    status: 'ACTIVE',
  };

  it('replays the original accepted response after the request has been accepted', async () => {
    const dto: CreateDonorResponseDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000012',
      status: DonorResponseStatus.ACCEPTED,
    };
    const response = {
      id: '00000000-0000-4000-8000-000000000013',
      donorUserId: user.id,
      requestId: '00000000-0000-4000-8000-000000000014',
      status: DonorResponseStatus.ACCEPTED,
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.DONOR,
          donorProfile: { id: '00000000-0000-4000-8000-000000000015', isAvailable: false },
        }),
      },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(response) },
      emergencyRequest: { findUnique: jest.fn() },
      $transaction: jest.fn(),
    } as unknown as PrismaService;
    const service = new DonorResponsesService(prisma);

    await expect(service.respond(user, response.requestId, dto)).resolves.toBe(response);
    expect(prisma.emergencyRequest.findUnique).not.toHaveBeenCalled();
  });

  it('creates one response, advances accepted requests, and records both activity events', async () => {
    const requestId = '00000000-0000-4000-8000-000000000016';
    const dto: CreateDonorResponseDto = {
      idempotencyKey: '00000000-0000-4000-8000-000000000017',
      status: DonorResponseStatus.ACCEPTED,
    };
    const created = { id: '00000000-0000-4000-8000-000000000018', status: dto.status };
    const tx = {
      donorResponse: { create: jest.fn().mockResolvedValue(created) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      emergencyRequest: { updateMany: jest.fn().mockResolvedValue({ count: 1 }) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: 'ACTIVE',
          role: UserRole.DONOR,
          donorProfile: {
            id: '00000000-0000-4000-8000-000000000019',
            isAvailable: true,
            bloodGroup: 'O_NEGATIVE',
          },
        }),
      },
      donorResponse: { findUnique: jest.fn().mockResolvedValue(null) },
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue({
          id: requestId,
          requestedBloodGroup: 'AB_POSITIVE',
          status: EmergencyRequestStatus.SEARCHING,
          expiresAt: null,
        }),
      },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    } as unknown as PrismaService;
    const service = new DonorResponsesService(prisma);

    await expect(service.respond(user, requestId, dto)).resolves.toBe(created);
    expect(tx.donorResponse.create).toHaveBeenCalled();
    expect(tx.emergencyRequest.updateMany).toHaveBeenCalled();
    expect(tx.requestActivity.create).toHaveBeenCalledTimes(2);
  });
});
