import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { AccountStatus, UserRole } from '@prisma/client';
import { Request } from 'express';
import * as jwt from 'jsonwebtoken';
import { PrismaService } from '../../modules/prisma/prisma.service';
import { AuthenticatedUser } from './authenticated-user';
import { deriveJwtKey } from './token-keys';

interface AccessTokenClaims extends jwt.JwtPayload {
  sub: string;
  tokenType: 'access';
}

type AuthenticatedRequest = Request & { user?: AuthenticatedUser };

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly prisma: PrismaService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const authorization = request.headers.authorization;
    const [scheme, token] = authorization?.split(' ') ?? [];
    if (scheme !== 'Bearer' || !token) {
      throw new UnauthorizedException('Authentication required.');
    }

    let claims: AccessTokenClaims;
    try {
      const verified = jwt.verify(token, deriveJwtKey('access'), {
        issuer: 'sistermesh-api',
        audience: 'sistermesh-clients',
      });
      if (typeof verified === 'string') {
        throw new UnauthorizedException('Invalid access token.');
      }
      claims = verified as AccessTokenClaims;
      if (!claims.sub || claims.tokenType !== 'access') {
        throw new UnauthorizedException('Invalid access token.');
      }
    } catch {
      throw new UnauthorizedException('Invalid or expired access token.');
    }

    // Resolve role and status from the database so role changes and account
    // suspension take effect without waiting for token expiry.
    const user = await this.prisma.user.findUnique({
      where: { id: claims.sub },
      select: {
        id: true,
        role: true,
        status: true,
        displayName: true,
        phoneNumber: true,
      },
    });
    if (!user || user.status !== AccountStatus.ACTIVE) {
      throw new UnauthorizedException('Invalid or expired access token.');
    }

    request.user = {
      id: user.id,
      role: user.role as UserRole,
      displayName: user.displayName,
      phoneNumber: user.phoneNumber,
    };
    return true;
  }
}
