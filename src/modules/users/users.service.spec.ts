import { AccountStatus, UserRole } from '@prisma/client';
import { UsersService } from './users.service';

describe('UsersService administrator controls', () => {
  it('prevents demoting the last active administrator', async () => {
    const tx = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: 'admin-id',
          role: UserRole.ADMIN,
          status: AccountStatus.ACTIVE,
        }),
        count: jest.fn().mockResolvedValue(1),
        update: jest.fn(),
      },
      donorProfile: { upsert: jest.fn() },
      healthWorkerProfile: { upsert: jest.fn() },
      auditLog: { create: jest.fn() },
    };
    const prisma = {
      $transaction: jest.fn(
        (callback: (transaction: typeof tx) => unknown) => callback(tx),
      ),
    };
    const service = new UsersService(prisma as never);

    await expect(
      service.setRole('other-admin', 'admin-id', UserRole.INDIVIDUAL),
    ).rejects.toMatchObject({ status: 409 });
    expect(tx.user.update).not.toHaveBeenCalled();
  });

  it('revokes refresh tokens when an account is suspended', async () => {
    const tx = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: 'user-id',
          role: UserRole.INDIVIDUAL,
          status: AccountStatus.ACTIVE,
        }),
        update: jest.fn().mockResolvedValue({ id: 'user-id' }),
        count: jest.fn(),
      },
      refreshToken: { updateMany: jest.fn().mockResolvedValue({ count: 2 }) },
      auditLog: { create: jest.fn().mockResolvedValue({}) },
    };
    const prisma = {
      $transaction: jest.fn(
        (callback: (transaction: typeof tx) => unknown) => callback(tx),
      ),
    };
    const service = new UsersService(prisma as never);

    await service.setStatus('admin-id', 'user-id', AccountStatus.SUSPENDED);
    expect(tx.refreshToken.updateMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { userId: 'user-id', revokedAt: null },
      }),
    );
  });
});
