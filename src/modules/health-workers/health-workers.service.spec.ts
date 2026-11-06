import {
  FacilityStatus,
  HealthWorkerVerificationStatus,
  UserRole,
} from '@prisma/client';
import { HealthWorkersService } from './health-workers.service';

describe('HealthWorkersService', () => {
  it('resets verification when a professional identifier changes', async () => {
    const prisma = {
      healthWorkerProfile: {
        findUnique: jest.fn().mockResolvedValue({
          professionalIdentifier: 'OLD-123',
        }),
        upsert: jest.fn().mockResolvedValue({ verificationStatus: 'PENDING' }),
      },
    };
    const service = new HealthWorkersService(prisma as never);

    await service.updateOwnProfile('worker-id', {
      professionalIdentifier: 'NEW-456',
    });

    expect(prisma.healthWorkerProfile.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { userId: 'worker-id' },
        update: expect.objectContaining({
          professionalIdentifier: 'NEW-456',
          verificationStatus: HealthWorkerVerificationStatus.PENDING,
        }),
      }),
    );
  });

  it('rejects assignment to an inactive facility', async () => {
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({ role: UserRole.HEALTH_WORKER }),
      },
      facility: {
        findUnique: jest.fn().mockResolvedValue({ status: FacilityStatus.INACTIVE }),
      },
      healthWorkerProfile: { upsert: jest.fn() },
    };
    const service = new HealthWorkersService(prisma as never);

    await expect(
      service.assignFacility('worker-id', {
        facilityId: '2c2f8854-a8d4-466c-8e4a-13b59f53ef2e',
      }),
    ).rejects.toMatchObject({ status: 403 });
    expect(prisma.healthWorkerProfile.upsert).not.toHaveBeenCalled();
  });
});
