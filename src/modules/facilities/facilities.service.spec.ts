import {
  FacilityStatus,
  HealthWorkerVerificationStatus,
  UserRole,
} from '@prisma/client';
import { FacilitiesService } from './facilities.service';

describe('FacilitiesService access control', () => {
  it('denies health workers access to facilities they are not verified for', async () => {
    const prisma = {
      facility: {
        findUnique: jest.fn().mockResolvedValue({
          id: 'facility-id',
          name: 'Community Hospital',
          status: FacilityStatus.ACTIVE,
        }),
      },
      healthWorkerProfile: {
        findUnique: jest.fn().mockResolvedValue({
          facilityId: 'different-facility',
          verificationStatus: HealthWorkerVerificationStatus.PENDING,
        }),
      },
    };
    const service = new FacilitiesService(prisma as never);

    await expect(
      service.getForUser('facility-id', {
        id: 'worker-id',
        role: UserRole.HEALTH_WORKER,
        displayName: 'Worker',
        phoneNumber: '+2348012345678',
      }),
    ).rejects.toMatchObject({ status: 403 });
  });

  it('lists only active facilities for non-administrators', async () => {
    const prisma = {
      facility: { findMany: jest.fn().mockResolvedValue([]) },
    };
    const service = new FacilitiesService(prisma as never);

    await service.listForUser({
      id: 'donor-id',
      role: UserRole.DONOR,
      displayName: 'Donor',
      phoneNumber: '+2348012345678',
    });

    expect(prisma.facility.findMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { status: FacilityStatus.ACTIVE },
      }),
    );
  });
});
