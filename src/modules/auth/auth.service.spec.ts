import { AccountStatus, BloodGroup, Prisma, UserRole } from '@prisma/client';
import * as bcrypt from 'bcrypt';
import { createHash } from 'node:crypto';
import { PrismaService } from '../prisma/prisma.service';
import { AccessTokenService } from './access-token.service';
import { AuthService } from './auth.service';
import { LoginDto } from './dto/login.dto';
import { RegisterDonorDto } from './dto/register-donor.dto';

describe('AuthService', () => {
  const user = {
    id: '00000000-0000-4000-8000-000000000031',
    role: UserRole.DONOR,
    status: AccountStatus.ACTIVE,
    displayName: 'Test Donor',
    phoneNumber: '+2348012345678',
    donorProfile: {
      id: '00000000-0000-4000-8000-000000000032',
      bloodGroup: BloodGroup.O_NEGATIVE,
      isAvailable: true,
      locality: 'Central district',
      isProfileComplete: true,
    },
    healthWorkerProfile: null,
  };
  const tokens = { issue: jest.fn().mockResolvedValue('signed-access-token') };

  beforeEach(() => jest.clearAllMocks());

  it('hashes the registration password and never returns its hash', async () => {
    const dto: RegisterDonorDto = {
      displayName: user.displayName,
      phoneNumber: user.phoneNumber,
      password: 'Correct horse battery staple 24',
      bloodGroup: BloodGroup.O_NEGATIVE,
      locality: 'Central district',
    };
    const prismaMock = {
      user: { create: jest.fn().mockResolvedValue(user) },
      refreshToken: { create: jest.fn().mockResolvedValue({}) },
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);

    const session = await service.registerDonor(dto);
    const createArg = prismaMock.user.create.mock.calls[0][0];
    const storedHash = createArg.data.passwordHash as string;

    expect(createArg.data.role).toBe(UserRole.DONOR);
    expect(storedHash).not.toBe(dto.password);
    await expect(bcrypt.compare(dto.password, storedHash)).resolves.toBe(true);
    expect(session.user).toEqual(user);
    expect(JSON.stringify(session.user)).not.toContain(storedHash);
    expect(session.refreshToken).toBeTruthy();
  });

  it('rejects duplicate phone registration', async () => {
    const prismaError = new Prisma.PrismaClientKnownRequestError('Unique constraint', {
      code: 'P2002',
      clientVersion: '6.19.3',
    });
    const prismaMock = {
      user: { create: jest.fn().mockRejectedValue(prismaError) },
      refreshToken: { create: jest.fn() },
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);

    await expect(
      service.registerDonor({
        displayName: 'Test Donor',
        phoneNumber: '+2348012345678',
        password: 'Correct horse battery staple 24',
        bloodGroup: BloodGroup.O_NEGATIVE,
      }),
    ).rejects.toMatchObject({ status: 409 });
    expect(prismaMock.refreshToken.create).not.toHaveBeenCalled();
  });

  it('registers health workers as unverified and without a facility assignment', async () => {
    const worker = {
      ...user,
      role: UserRole.HEALTH_WORKER,
      donorProfile: null,
      healthWorkerProfile: {
        id: '00000000-0000-4000-8000-000000000034',
        designation: 'Nurse',
        professionalId: null,
        verificationStatus: 'PENDING',
        verifiedAt: null,
        facilityId: null,
      },
    };
    const prismaMock = {
      user: { create: jest.fn().mockResolvedValue(worker) },
      refreshToken: { create: jest.fn().mockResolvedValue({}) },
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);

    const session = await service.registerHealthWorker({
      displayName: 'Health Worker',
      phoneNumber: '+2348012345679',
      password: 'Correct horse battery staple 24',
    });
    const createData = prismaMock.user.create.mock.calls[0][0].data;

    expect(createData.role).toBe(UserRole.HEALTH_WORKER);
    expect(createData.healthWorkerProfile.create.verificationStatus).toBe('PENDING');
    expect(createData.healthWorkerProfile.create.facilityId).toBeNull();
    expect(session.user.role).toBe(UserRole.HEALTH_WORKER);
  });

  it('authenticates valid credentials and rejects invalid credentials generically', async () => {
    const password = 'Correct horse battery staple 24';
    const passwordHash = await bcrypt.hash(password, 4);
    const prismaMock = {
      user: { findUnique: jest.fn().mockResolvedValue({ ...user, passwordHash }) },
      refreshToken: { create: jest.fn().mockResolvedValue({}) },
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);
    const credentials: LoginDto = { phoneNumber: user.phoneNumber, password };

    await expect(service.login(credentials)).resolves.toMatchObject({
      accessToken: 'signed-access-token',
      user,
    });
    await expect(service.login({ ...credentials, password: 'incorrect password value' })).rejects.toMatchObject({
      status: 401,
      message: 'Phone number or password is incorrect.',
    });
  });

  it('rotates refresh tokens and persists only the replacement token hash', async () => {
    const oldToken = 'old-refresh-token-value';
    const oldHash = createHash('sha256').update(oldToken).digest('hex');
    const tx = {
      refreshToken: {
        updateMany: jest.fn().mockResolvedValue({ count: 1 }),
        create: jest.fn().mockResolvedValue({}),
      },
    };
    const prismaMock = {
      refreshToken: { findUnique: jest.fn().mockResolvedValue({
        id: '00000000-0000-4000-8000-000000000033',
        userId: user.id,
        tokenHash: oldHash,
        revokedAt: null,
        expiresAt: new Date(Date.now() + 60_000),
        user,
      }) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);

    const session = await service.refresh(oldToken);
    const newTokenHash = tx.refreshToken.create.mock.calls[0][0].data.tokenHash as string;
    expect(session.refreshToken).not.toBe(oldToken);
    expect(newTokenHash).not.toBe(session.refreshToken);
    expect(newTokenHash).toHaveLength(64);
    expect(tx.refreshToken.updateMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: expect.objectContaining({ id: '00000000-0000-4000-8000-000000000033' }) }),
    );
  });

  it('revokes the presented refresh session on logout without storing the raw token', async () => {
    const rawToken = 'raw-refresh-token-value';
    const prismaMock = {
      refreshToken: { updateMany: jest.fn().mockResolvedValue({ count: 1 }) },
    };
    const service = new AuthService(prismaMock as unknown as PrismaService, tokens as unknown as AccessTokenService);

    await service.logout(rawToken);
    expect(prismaMock.refreshToken.updateMany).toHaveBeenCalledWith({
      where: {
        tokenHash: createHash('sha256').update(rawToken).digest('hex'),
        revokedAt: null,
      },
      data: { revokedAt: expect.any(Date) },
    });
  });
});
