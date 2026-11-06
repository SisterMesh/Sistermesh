import {
  CanActivate,
  ExecutionContext,
  ForbiddenException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser } from './authenticated-user';
import { REQUIRED_ROLES_KEY } from './roles.decorator';
import { PrismaService } from '../modules/prisma/prisma.service';

@Injectable()
export class RolesGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly prisma: PrismaService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const user = context
      .switchToHttp()
      .getRequest<{ user?: AuthenticatedUser }>().user;
    if (!user?.id) {
      throw new UnauthorizedException('Authentication is required.');
    }
    if (user.status !== 'ACTIVE') {
      throw new ForbiddenException('This account is not active.');
    }

    const roles = this.reflector.getAllAndOverride<UserRole[]>(
      REQUIRED_ROLES_KEY,
      [context.getHandler(), context.getClass()],
    );
    if (roles?.length && !roles.includes(user.role)) {
      throw new ForbiddenException('Your account cannot perform this action.');
    }
    if (roles?.includes(UserRole.HEALTH_WORKER) && user.role === UserRole.HEALTH_WORKER) {
      const profile = await this.prisma.healthWorkerProfile.findUnique({
        where: { userId: user.id },
        select: { verificationStatus: true },
      });
      if (profile?.verificationStatus !== 'VERIFIED') {
        throw new ForbiddenException('Your health worker profile has not been verified.');
      }
    }
    return true;
  }
}
