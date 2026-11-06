import { AccountStatus, UserRole } from '@prisma/client';
import * as argon2 from 'argon2';
import * as jwt from 'jsonwebtoken';
import { AuthService } from './auth.service';
import { deriveJwtKey } from '../../common/auth/token-keys';

describe('AuthService', () => {
  const originalSessionSecret = process.env.SESSION_SECRET;
  const user = {
    id: '29e4378f-85ab-4b4b-8ce3-6e721e45338a',
    role: UserRole.DONOR,
    displayName: 'Amina Donor',
    phoneNumber: '+2348012345678',
    createdAt: new Date('2026-01-01T00:00:00.000Z'),
  };
  let service: AuthService;
  let prisma: {
    user: { findUnique: jest.Mock };
    refreshToken: { create: jest.Mock; updateMany: jest.Mock };
    $transaction: jest.Mock;
  };
  let tx: {
    user: { create: jest.Mock };
    donorProfile: { create: jest.Mock };
    healthWorkerProfile: { create: jest.Mock };
    refreshToken: {
      findUnique: jest.Mock;
      updateMany: jest.Mock;
      create: jest.Mock;
    };
  };

  beforeEach(() => {
    process.env.SESSION_SECRET = 'test-only-session-secret-which-is-long-enough';
    tx = {
      user: { create: jest.fn().mockResolvedValue(user) },
      donorProfile: { create: jest.fn().mockResolvedValue({}) },
      healthWorkerProfile: { create: jest.fn().mockResolvedValue({}) },
      refreshToken: {
        findUnique: jest.fn(),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
        create: jest.fn().mockResolvedValue({}),
      },
    };
    prisma = {
      user: { findUnique: jest.fn().mockResolvedValue(null) },
      refreshToken: {
        create: jest.fn().mockResolvedValue({}),
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      },
      $transaction: jest.fn(async (operation: (transaction: typeof tx) => unknown) =>
        operation(tx),
      ),
    };
    service = new AuthService(prisma as never);
  });

  afterAll(() => {
    if (originalSessionSecret === undefined) {
      delete process.env.SESSION_SECRET;
    } else {
      process.env.SESSION_SECRET = originalSessionSecret;
    }
  });

  it('registers a donor with an Argon2id hash and creates one profile', async () => {
    const session = await service.register({
      displayName: 'Amina Donor',
      phoneNumber: '+2348012345678',
      password: 'Strong password 2026',
      role: 'DONOR',
    });

    const createdUser = tx.user.create.mock.calls[0][0].data;
    expect(createdUser.passwordHash).not.toBe('Strong password 2026');
    await expect(
      argon2.verify(createdUser.passwordHash, 'Strong password 2026'),
    ).resolves.toBe(true);
    expect(tx.donorProfile.create).toHaveBeenCalledWith({
      data: { userId: user.id },
    });
    expect(tx.healthWorkerProfile.create).not.toHaveBeenCalled();
    expect(session.user).not.toHaveProperty('passwordHash');
    expect(session).toHaveProperty('refreshToken');
  });

  it('rejects administrator registration even if called outside DTO validation', async () => {
    await expect(
      service.register({
        displayName: 'Unexpected Admin',
        phoneNumber: '+2348012345678',
        password: 'Strong password 2026',
        role: 'ADMIN',
      } as never),
    ).rejects.toMatchObject({ status: 400 });
    expect(prisma.user.findUnique).not.toHaveBeenCalled();
  });

  it('rejects duplicate phone numbers before hashing a password', async () => {
    prisma.user.findUnique.mockResolvedValue({ id: user.id });
    await expect(
      service.register({
        displayName: 'Amina Donor',
        phoneNumber: user.phoneNumber,
        password: 'Strong password 2026',
        role: 'DONOR',
      }),
    ).rejects.toMatchObject({ status: 409 });
    expect(tx.user.create).not.toHaveBeenCalled();
  });

  it('returns a safe session for valid credentials', async () => {
    const passwordHash = await argon2.hash('Strong password 2026', {
      memoryCost: 1024,
      timeCost: 1,
      parallelism: 1,
    });
    prisma.user.findUnique.mockResolvedValue({
      ...user,
      status: AccountStatus.ACTIVE,
      passwordHash,
    });

    const session = await service.login({
      phoneNumber: user.phoneNumber,
      password: 'Strong password 2026',
    });
    expect(
      (jwt.verify(session.accessToken, deriveJwtKey('access')) as jwt.JwtPayload)
        .tokenType,
    ).toBe('access');
    expect(session.user).not.toHaveProperty('passwordHash');
    expect(prisma.refreshToken.create).toHaveBeenCalledTimes(1);
  });

  it('returns the same generic error for incorrect credentials', async () => {
    prisma.user.findUnique.mockResolvedValue(null);
    await expect(
      service.login({
        phoneNumber: user.phoneNumber,
        password: 'wrong password',
      }),
    ).rejects.toMatchObject({ status: 401 });
    expect(prisma.refreshToken.create).not.toHaveBeenCalled();
  });

  it('rotates a valid refresh token and rejects reuse', async () => {
    tx.refreshToken.findUnique.mockResolvedValue({
      id: 'refresh-row',
      userId: user.id,
      revokedAt: null,
      expiresAt: new Date(Date.now() + 60_000),
      user: { ...user, status: AccountStatus.ACTIVE },
    });

    const session = await service.rotateRefreshToken('old-refresh-token');
    expect(session.refreshToken).not.toBe('old-refresh-token');
    expect(tx.refreshToken.updateMany).toHaveBeenCalledTimes(1);
    expect(tx.refreshToken.create).toHaveBeenCalledTimes(1);
    expect(tx.refreshToken.create.mock.calls[0][0].data.tokenHash).not.toBe(
      session.refreshToken,
    );

    tx.refreshToken.findUnique.mockResolvedValue(null);
    await expect(
      service.rotateRefreshToken('old-refresh-token'),
    ).rejects.toMatchObject({ status: 401 });
  });

  it('revokes a refresh token at logout without returning it', async () => {
    await expect(service.logout('refresh-token-value')).resolves.toBeUndefined();
    expect(prisma.refreshToken.updateMany).toHaveBeenCalledTimes(1);
    expect(prisma.refreshToken.updateMany.mock.calls[0][0].where.tokenHash).not.toBe(
      'refresh-token-value',
    );
  });
});
