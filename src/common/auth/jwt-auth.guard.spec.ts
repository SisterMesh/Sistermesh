import { ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { AccountStatus, UserRole } from '@prisma/client';
import * as jwt from 'jsonwebtoken';
import { JwtAuthGuard } from './jwt-auth.guard';
import { deriveJwtKey } from './token-keys';

describe('JwtAuthGuard', () => {
  const context = (authorization?: string) =>
    ({
      switchToHttp: () => ({
        getRequest: () => ({ headers: { authorization } }),
      }),
    }) as unknown as ExecutionContext;

  it('loads the current role from the database for a valid token', async () => {
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: 'user-id',
          role: UserRole.HEALTH_WORKER,
          status: AccountStatus.ACTIVE,
          displayName: 'Health Worker',
          phoneNumber: '+2348012345678',
        }),
      },
    };
    const guard = new JwtAuthGuard(prisma as never);
    const token = jwt.sign(
      { sub: 'user-id', tokenType: 'access' },
      deriveJwtKey('access'),
      {
        expiresIn: 60,
        issuer: 'sistermesh-api',
        audience: 'sistermesh-clients',
      },
    );
    const request = { headers: { authorization: `Bearer ${token}` } } as {
      headers: { authorization: string };
      user?: { role: UserRole };
    };
    const requestContext = {
      switchToHttp: () => ({
        getRequest: () => request,
      }),
    } as unknown as ExecutionContext;

    await expect(guard.canActivate(requestContext)).resolves.toBe(true);
    expect(request.user?.role).toBe(UserRole.HEALTH_WORKER);
  });

  it('rejects a missing, invalid, or expired bearer token', async () => {
    const prisma = { user: { findUnique: jest.fn() } };
    const guard = new JwtAuthGuard(prisma as never);
    const expiredToken = jwt.sign(
      { sub: 'user-id', tokenType: 'access' },
      deriveJwtKey('access'),
      {
        expiresIn: -1,
        issuer: 'sistermesh-api',
        audience: 'sistermesh-clients',
      },
    );

    await expect(guard.canActivate(context())).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
    await expect(
      guard.canActivate(context(`Bearer ${expiredToken}`)),
    ).rejects.toBeInstanceOf(UnauthorizedException);
  });
});
