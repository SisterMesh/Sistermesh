import { ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { UserRole } from '@prisma/client';
import { RolesGuard } from './roles.guard';

describe('RolesGuard', () => {
  const context = (role?: UserRole) =>
    ({
      getHandler: jest.fn(),
      getClass: jest.fn(),
      switchToHttp: () => ({
        getRequest: () => ({ user: role ? { role } : undefined }),
      }),
    }) as unknown as ExecutionContext;

  it('allows an administrator and rejects a donor for admin-only routes', () => {
    const reflector = {
      getAllAndOverride: jest.fn().mockReturnValue([UserRole.ADMIN]),
    } as unknown as Reflector;
    const guard = new RolesGuard(reflector);

    expect(guard.canActivate(context(UserRole.ADMIN))).toBe(true);
    expect(guard.canActivate(context(UserRole.DONOR))).toBe(false);
    expect(guard.canActivate(context())).toBe(false);
  });

  it('rejects donors and unverified health-worker roles on routes requiring another role', () => {
    const reflector = {
      getAllAndOverride: jest.fn().mockReturnValue([UserRole.HEALTH_WORKER]),
    } as unknown as Reflector;
    const guard = new RolesGuard(reflector);

    expect(guard.canActivate(context(UserRole.DONOR))).toBe(false);
    expect(guard.canActivate(context(UserRole.ADMIN))).toBe(false);
  });
});
