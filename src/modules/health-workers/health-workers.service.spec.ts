import {
  AccountStatus,
  HealthWorkerVerificationStatus,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { HealthWorkersService } from './health-workers.service';

describe('HealthWorkersService', () => {
  const worker: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000071',
    role: UserRole.HEALTH_WORKER,
    status: 'ACTIVE',
  };

  it('creates a worker profile pending admin verification and without facility authority', async () => {
    const profile = {
      id: '00000000-0000-4000-8000-000000000072',
      userId: worker.id,
      facilityId: null,
      verificationStatus: HealthWorkerVerificationStatus.PENDING,
    };
    const prismaMock = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: worker.id,
          role: UserRole.HEALTH_WORKER,
          status: AccountStatus.ACTIVE,
        }),
      },
      healthWorkerProfile: { create: jest.fn().mockResolvedValue(profile) },
    };
    const service = new HealthWorkersService(prismaMock as unknown as PrismaService);

    await expect(service.createMine(worker, { professionalId: 'LIC-123', designation: 'Nurse' })).resolves.toBe(profile);
    expect(prismaMock.healthWorkerProfile.create.mock.calls[0][0].data).toMatchObject({
      userId: worker.id,
      professionalId: 'LIC-123',
      verificationStatus: HealthWorkerVerificationStatus.PENDING,
    });
    expect(prismaMock.healthWorkerProfile.create.mock.calls[0][0].data.facilityId).toBeUndefined();
  });

  it('does not permit verification without a professional identifier', async () => {
    const prismaMock = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: worker.id,
          role: UserRole.HEALTH_WORKER,
          status: AccountStatus.ACTIVE,
          healthWorkerProfile: {
            id: '00000000-0000-4000-8000-000000000073',
            professionalId: null,
          },
        }),
      },
      $transaction: jest.fn(),
    };
    const service = new HealthWorkersService(prismaMock as unknown as PrismaService);

    await expect(
      service.setVerification(
        { id: '00000000-0000-4000-8000-000000000074', role: UserRole.ADMIN, status: 'ACTIVE' },
        worker.id,
        { verificationStatus: HealthWorkerVerificationStatus.VERIFIED },
      ),
    ).rejects.toMatchObject({ status: 409 });
    expect(prismaMock.$transaction).not.toHaveBeenCalled();
  });
});
