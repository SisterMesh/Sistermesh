import {
  BadRequestException,
  ConflictException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { AccountStatus, Prisma, UserRole } from '@prisma/client';
import * as argon2 from 'argon2';
import { createHash, randomBytes } from 'node:crypto';
import * as jwt from 'jsonwebtoken';
import { PrismaService } from '../prisma/prisma.service';
import { ChangePasswordDto } from './dto/change-password.dto';
import { LoginDto } from './dto/login.dto';
import { RegisterDto } from './dto/register.dto';
import { deriveJwtKey } from '../../common/auth/token-keys';

const publicUserSelect = {
  id: true,
  role: true,
  displayName: true,
  phoneNumber: true,
  createdAt: true,
} satisfies Prisma.UserSelect;

export type PublicUser = Prisma.UserGetPayload<{
  select: typeof publicUserSelect;
}>;

export interface AuthSession {
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  user: PublicUser;
}

const ACCESS_TOKEN_SECONDS = 15 * 60;
const REFRESH_TOKEN_DAYS = 30;

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
  ) {
    deriveJwtKey('access');
    deriveJwtKey('refresh');
  }

  async register(dto: RegisterDto): Promise<AuthSession> {
    if (
      dto.role !== UserRole.DONOR &&
      dto.role !== UserRole.HEALTH_WORKER
    ) {
      throw new BadRequestException(
        'Only donor and health-worker roles can register publicly.',
      );
    }

    const existing = await this.prisma.user.findUnique({
      where: { phoneNumber: dto.phoneNumber },
      select: { id: true },
    });
    if (existing) {
      throw new ConflictException('An account with this phone number already exists.');
    }

    const passwordHash = await argon2.hash(dto.password, {
      type: argon2.argon2id,
      memoryCost: 65_536,
      timeCost: 3,
      parallelism: 1,
    });

    try {
      const user = await this.prisma.$transaction(async (tx) => {
        const created = await tx.user.create({
          data: {
            displayName: dto.displayName.trim(),
            phoneNumber: dto.phoneNumber,
            passwordHash,
            role: dto.role as UserRole,
          },
          select: publicUserSelect,
        });

        if (dto.role === UserRole.DONOR) {
          await tx.donorProfile.create({ data: { userId: created.id } });
        } else {
          await tx.healthWorkerProfile.create({
            data: { userId: created.id },
          });
        }
        return created;
      });
      return this.createSession(user);
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('An account with this phone number already exists.');
      }
      throw error;
    }
  }

  async login(dto: LoginDto): Promise<AuthSession> {
    const user = await this.prisma.user.findUnique({
      where: { phoneNumber: dto.phoneNumber },
      select: {
        ...publicUserSelect,
        status: true,
        passwordHash: true,
      },
    });

    if (
      !user ||
      user.status !== AccountStatus.ACTIVE ||
      !user.passwordHash ||
      !(await this.verifyPassword(dto.password, user.passwordHash))
    ) {
      throw new UnauthorizedException('Invalid phone number or password.');
    }

    const publicUser: PublicUser = {
      id: user.id,
      role: user.role,
      displayName: user.displayName,
      phoneNumber: user.phoneNumber,
      createdAt: user.createdAt,
    };
    return this.createSession(publicUser);
  }

  async rotateRefreshToken(token: string): Promise<AuthSession> {
    const currentHash = this.hashRefreshToken(token);
    const nextToken = randomBytes(32).toString('base64url');
    const now = new Date();
    const expiresAt = new Date(
      now.getTime() + REFRESH_TOKEN_DAYS * 24 * 60 * 60 * 1000,
    );

    const rotation = await this.prisma.$transaction(async (tx) => {
      const current = await tx.refreshToken.findUnique({
        where: { tokenHash: currentHash },
        include: {
          user: {
            select: {
              ...publicUserSelect,
              status: true,
            },
          },
        },
      });
      if (!current || current.revokedAt || current.expiresAt <= now) return null;

      const revoked = await tx.refreshToken.updateMany({
        where: {
          id: current.id,
          revokedAt: null,
          expiresAt: { gt: now },
        },
        data: { revokedAt: now },
      });
      if (revoked.count !== 1) return null;
      if (current.user.status !== AccountStatus.ACTIVE) return null;

      await tx.refreshToken.create({
        data: {
          userId: current.userId,
          tokenHash: this.hashRefreshToken(nextToken),
          expiresAt,
        },
      });
      const publicUser: PublicUser = {
        id: current.user.id,
        role: current.user.role,
        displayName: current.user.displayName,
        phoneNumber: current.user.phoneNumber,
        createdAt: current.user.createdAt,
      };
      return { user: publicUser, refreshToken: nextToken };
    });

    if (!rotation) {
      throw new UnauthorizedException('Invalid or expired refresh token.');
    }
    return this.createAccessSession(rotation.user, rotation.refreshToken);
  }

  async logout(token?: string): Promise<void> {
    if (!token) return;
    await this.prisma.refreshToken.updateMany({
      where: {
        tokenHash: this.hashRefreshToken(token),
        revokedAt: null,
      },
      data: { revokedAt: new Date() },
    });
  }

  async changePassword(
    userId: string,
    dto: ChangePasswordDto,
  ): Promise<void> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { passwordHash: true, status: true },
    });
    if (
      !user ||
      user.status !== AccountStatus.ACTIVE ||
      !user.passwordHash ||
      !(await this.verifyPassword(dto.currentPassword, user.passwordHash))
    ) {
      throw new UnauthorizedException('Current password is incorrect.');
    }

    const passwordHash = await argon2.hash(dto.newPassword, {
      type: argon2.argon2id,
      memoryCost: 65_536,
      timeCost: 3,
      parallelism: 1,
    });
    const now = new Date();
    await this.prisma.$transaction([
      this.prisma.user.update({
        where: { id: userId },
        data: { passwordHash },
      }),
      this.prisma.refreshToken.updateMany({
        where: { userId, revokedAt: null },
        data: { revokedAt: now },
      }),
    ]);
  }

  async getCurrentUser(userId: string): Promise<PublicUser> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: publicUserSelect,
    });
    if (!user) throw new UnauthorizedException('Account is no longer available.');
    return user;
  }

  private async createSession(user: PublicUser): Promise<AuthSession> {
    const refreshToken = randomBytes(32).toString('base64url');
    await this.prisma.refreshToken.create({
      data: {
        userId: user.id,
        tokenHash: this.hashRefreshToken(refreshToken),
        expiresAt: new Date(
          Date.now() + REFRESH_TOKEN_DAYS * 24 * 60 * 60 * 1000,
        ),
      },
    });
    return this.createAccessSession(user, refreshToken);
  }

  private createAccessSession(
    user: PublicUser,
    refreshToken: string,
  ): AuthSession {
    const accessToken = jwt.sign(
      { sub: user.id, tokenType: 'access' },
      deriveJwtKey('access'),
      {
        expiresIn: ACCESS_TOKEN_SECONDS,
        issuer: 'sistermesh-api',
        audience: 'sistermesh-clients',
      },
    );
    return {
      accessToken,
      expiresIn: ACCESS_TOKEN_SECONDS,
      refreshToken,
      user,
    };
  }

  private hashRefreshToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }

  private async verifyPassword(password: string, hash: string): Promise<boolean> {
    try {
      return await argon2.verify(hash, password);
    } catch {
      return false;
    }
  }
}
