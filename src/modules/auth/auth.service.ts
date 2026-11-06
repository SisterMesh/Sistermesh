import {
  BadRequestException,
  ConflictException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import {
  AccountStatus,
  Prisma,
  UserRole,
} from '@prisma/client';
import * as argon2 from 'argon2';
import * as bcrypt from 'bcrypt';
import { createHash, randomBytes } from 'node:crypto';
import { PrismaService } from '../prisma/prisma.service';
import { AccessTokenService } from './access-token.service';
import { LoginDto } from './dto/login.dto';
import { RegisterDonorDto } from './dto/register-donor.dto';
import { RegisterHealthWorkerDto } from './dto/register-health-worker.dto';
import { RegisterDto } from './dto/register.dto';
import { ChangePasswordDto } from './dto/change-password.dto';
import { REFRESH_TOKEN_TTL_SECONDS } from './refresh-cookie';

const BCRYPT_COST = 12;

const PUBLIC_USER_SELECT = {
  id: true,
  role: true,
  status: true,
  displayName: true,
  phoneNumber: true,
  donorProfile: {
    select: {
      id: true,
      bloodGroup: true,
      isAvailable: true,
      locality: true,
      isProfileComplete: true,
    },
  },
  healthWorkerProfile: {
    select: {
      id: true,
      designation: true,
      professionalId: true,
      verificationStatus: true,
      verifiedAt: true,
      facilityId: true,
    },
  },
} satisfies Prisma.UserSelect;

type PublicUserRecord = Prisma.UserGetPayload<{ select: typeof PUBLIC_USER_SELECT }>;

export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  user: PublicUserRecord;
}

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly accessTokens: AccessTokenService,
  ) {}

  async register(dto: RegisterDto): Promise<AuthSession> {
    if (dto.role !== UserRole.DONOR && dto.role !== UserRole.HEALTH_WORKER) {
      throw new BadRequestException('Only donor and health-worker roles can register.');
    }
    this.validateRegistration(dto.displayName, dto.password);
    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_COST);
    try {
      const user = await this.prisma.user.create({
        data: {
          role: dto.role as UserRole,
          status: AccountStatus.ACTIVE,
          displayName: dto.displayName.trim(),
          phoneNumber: dto.phoneNumber,
          passwordHash,
          ...(dto.role === UserRole.DONOR
            ? {
                donorProfile: {
                  create: { bloodGroup: null, isProfileComplete: false },
                },
              }
            : {
                healthWorkerProfile: {
                  create: { verificationStatus: 'PENDING', facilityId: null },
                },
              }),
        },
        select: PUBLIC_USER_SELECT,
      });
      return this.createSession(user);
    } catch (error) {
      if (this.isUniqueConstraint(error)) {
        throw new ConflictException('An account with this phone number already exists.');
      }
      throw error;
    }
  }

  async registerDonor(dto: RegisterDonorDto): Promise<AuthSession> {
    this.validateRegistration(dto.displayName, dto.password);
    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_COST);
    try {
      const user = await this.prisma.user.create({
        data: {
          role: UserRole.DONOR,
          status: AccountStatus.ACTIVE,
          displayName: dto.displayName.trim(),
          phoneNumber: dto.phoneNumber,
          passwordHash,
          donorProfile: {
            create: {
              bloodGroup: dto.bloodGroup,
              locality: dto.locality?.trim() || null,
               isProfileComplete: Boolean(dto.bloodGroup && dto.locality?.trim()),
            },
          },
        },
        select: PUBLIC_USER_SELECT,
      });
      return this.createSession(user);
    } catch (error) {
      if (this.isUniqueConstraint(error)) {
        throw new ConflictException('An account already uses this phone number or profile identifier.');
      }
      throw error;
    }
  }

  async registerHealthWorker(dto: RegisterHealthWorkerDto): Promise<AuthSession> {
    this.validateRegistration(dto.displayName, dto.password);
    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_COST);
    try {
      const user = await this.prisma.user.create({
        data: {
          role: UserRole.HEALTH_WORKER,
          status: AccountStatus.ACTIVE,
          displayName: dto.displayName.trim(),
          phoneNumber: dto.phoneNumber,
          passwordHash,
          healthWorkerProfile: {
            create: {
              designation: dto.designation?.trim() || null,
              professionalId: dto.professionalId?.trim() || null,
              verificationStatus: 'PENDING',
              facilityId: null,
            },
          },
        },
        select: PUBLIC_USER_SELECT,
      });
      return this.createSession(user);
    } catch (error) {
      if (this.isUniqueConstraint(error)) {
        throw new ConflictException('An account already uses this phone number or professional ID.');
      }
      throw error;
    }
  }

  async login(dto: LoginDto): Promise<AuthSession> {
    const user = await this.prisma.user.findUnique({
      where: { phoneNumber: dto.phoneNumber },
      select: { ...PUBLIC_USER_SELECT, passwordHash: true },
    });
    if (
      !user ||
      !user.passwordHash ||
      user.status !== AccountStatus.ACTIVE ||
      !(await this.verifyPassword(dto.password, user.passwordHash))
    ) {
      throw new UnauthorizedException('Phone number or password is incorrect.');
    }
    const safeUser: PublicUserRecord = {
      id: user.id,
      role: user.role,
      status: user.status,
      displayName: user.displayName,
      phoneNumber: user.phoneNumber,
      donorProfile: user.donorProfile,
      healthWorkerProfile: user.healthWorkerProfile,
    };
    return this.createSession(safeUser);
  }

  async refresh(rawToken: string): Promise<AuthSession> {
    const tokenHash = this.hashRefreshToken(rawToken);
    const current = await this.prisma.refreshToken.findUnique({
      where: { tokenHash },
      include: { user: { select: PUBLIC_USER_SELECT } },
    });
    const now = new Date();
    if (!current) throw new UnauthorizedException('Refresh session is invalid or expired.');
    if (current.revokedAt) {
      await this.prisma.refreshToken.updateMany({
        where: { userId: current.userId, revokedAt: null },
        data: { revokedAt: now },
      });
      throw new UnauthorizedException('Refresh session is invalid or expired.');
    }
    if (current.expiresAt <= now || current.user.status !== AccountStatus.ACTIVE) {
      await this.prisma.refreshToken.updateMany({
        where: { id: current.id, revokedAt: null },
        data: { revokedAt: now },
      });
      throw new UnauthorizedException('Refresh session is invalid or expired.');
    }

    const nextToken = this.newRefreshToken();
    const rotated = await this.prisma.$transaction(async (tx) => {
      const revoked = await tx.refreshToken.updateMany({
        where: { id: current.id, revokedAt: null, expiresAt: { gt: now } },
        data: { revokedAt: now },
      });
      if (!revoked.count) {
        await tx.refreshToken.updateMany({
          where: { userId: current.userId, revokedAt: null },
          data: { revokedAt: now },
        });
        return false;
      }
      await tx.refreshToken.create({
        data: {
          userId: current.userId,
          tokenHash: this.hashRefreshToken(nextToken),
          expiresAt: new Date(now.getTime() + REFRESH_TOKEN_TTL_SECONDS * 1000),
        },
      });
      return true;
    });
    if (!rotated) throw new UnauthorizedException('Refresh session is invalid or expired.');
    return {
      accessToken: await this.accessTokens.issue(current.userId),
      refreshToken: nextToken,
      user: current.user,
    };
  }

  rotateRefreshToken(rawToken: string): Promise<AuthSession> {
    return this.refresh(rawToken);
  }

  async logout(rawToken?: string): Promise<void> {
    if (!rawToken) return;
    await this.prisma.refreshToken.updateMany({
      where: { tokenHash: this.hashRefreshToken(rawToken), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }

  async changePassword(userId: string, dto: ChangePasswordDto): Promise<void> {
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
    this.validateRegistration('valid', dto.newPassword);
    const passwordHash = await bcrypt.hash(dto.newPassword, BCRYPT_COST);
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

  async getProfile(userId: string): Promise<PublicUserRecord> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: PUBLIC_USER_SELECT,
    });
    if (!user || user.status !== AccountStatus.ACTIVE) {
      throw new UnauthorizedException('The account is not active.');
    }
    return user;
  }

  private async createSession(user: PublicUserRecord): Promise<AuthSession> {
    const refreshToken = this.newRefreshToken();
    await this.prisma.refreshToken.create({
      data: {
        userId: user.id,
        tokenHash: this.hashRefreshToken(refreshToken),
        expiresAt: new Date(Date.now() + REFRESH_TOKEN_TTL_SECONDS * 1000),
      },
    });
    return {
      accessToken: await this.accessTokens.issue(user.id),
      refreshToken,
      user,
    };
  }

  private newRefreshToken(): string {
    return randomBytes(48).toString('base64url');
  }

  private validateRegistration(displayName: string, password: string): void {
    if (!displayName.trim()) throw new BadRequestException('Display name is required.');
    if (Buffer.byteLength(password, 'utf8') > 72) {
      throw new BadRequestException('Password must not exceed 72 UTF-8 bytes.');
    }
  }

  private hashRefreshToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }

  private async verifyPassword(password: string, hash: string): Promise<boolean> {
    try {
      if (hash.startsWith('$argon2')) {
        return await argon2.verify(hash, password);
      }
      if (Buffer.byteLength(password, 'utf8') > 72) return false;
      return await bcrypt.compare(password, hash);
    } catch {
      return false;
    }
  }

  private isUniqueConstraint(error: unknown): error is Prisma.PrismaClientKnownRequestError {
    return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
  }
}
