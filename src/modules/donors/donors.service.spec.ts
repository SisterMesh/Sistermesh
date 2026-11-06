import { BloodGroup } from '@prisma/client';
import { DonorsService } from './donors.service';

describe('DonorsService', () => {
  it('updates only the authenticated donor profile and computes completion', async () => {
    const prisma = {
      donorProfile: {
        findUnique: jest.fn().mockResolvedValue({
          bloodGroup: null,
          locality: null,
        }),
        upsert: jest.fn().mockResolvedValue({
          userId: 'own-user-id',
          bloodGroup: BloodGroup.O_POSITIVE,
          locality: 'Rumuola',
          isAvailable: true,
          isProfileComplete: true,
        }),
      },
    };
    const service = new DonorsService(prisma as never);

    await service.updateOwnProfile('own-user-id', {
      bloodGroup: BloodGroup.O_POSITIVE,
      locality: ' Rumuola ',
    });

    expect(prisma.donorProfile.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { userId: 'own-user-id' },
        create: expect.objectContaining({
          userId: 'own-user-id',
          locality: 'Rumuola',
          isProfileComplete: true,
        }),
        update: expect.objectContaining({
          locality: 'Rumuola',
          isProfileComplete: true,
        }),
      }),
    );
  });
});
