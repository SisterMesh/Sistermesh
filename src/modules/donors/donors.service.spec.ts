import { AccountStatus, BloodGroup, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { DonorsService } from './donors.service';

describe('DonorsService', () => {
  const actor: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000061',
    role: UserRole.DONOR,
    status: 'ACTIVE',
  };

  it('creates a profile only for the authenticated donor and marks completion from general locality', async () => {
    const profile = {
      id: '00000000-0000-4000-8000-000000000062',
      userId: actor.id,
      bloodGroup: BloodGroup.A_POSITIVE,
      locality: 'North community',
      isAvailable: true,
      isProfileComplete: true,
    };
    const prismaMock = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: actor.id,
          role: UserRole.DONOR,
          status: AccountStatus.ACTIVE,
        }),
      },
      donorProfile: { create: jest.fn().mockResolvedValue(profile) },
    };
    const service = new DonorsService(prismaMock as unknown as PrismaService);

    await expect(
      service.createMine(actor, { bloodGroup: BloodGroup.A_POSITIVE, locality: 'North community' }),
    ).resolves.toBe(profile);
    expect(prismaMock.donorProfile.create.mock.calls[0][0].data).toMatchObject({
      userId: actor.id,
      bloodGroup: BloodGroup.A_POSITIVE,
      locality: 'North community',
      isProfileComplete: true,
    });
  });
});
