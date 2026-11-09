import { ConflictException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { AccountStatus, Prisma, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { UpdateUserProfileDto } from './dto/update-user-profile.dto';
import { UpdateAccountStatusDto } from './dto/update-account-status.dto';

const USER_PROFILE_SELECT = {
  id: true,
  role: true,
  status: true,
  displayName: true,
  phoneNumber: true,
  createdAt: true,
  updatedAt: true,
} satisfies Prisma.UserSelect;

@Injectable()
export class UsersService {
  constructor(private readonly prisma: PrismaService) {}

  async getMine(user: AuthenticatedUser) {
    const record = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: {
        ...USER_PROFILE_SELECT,
        donorProfile: { select: { id: true, bloodGroup: true, isAvailable: true, locality: true, isProfileComplete: true } },
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
      },
    });
    if (!record || record.status !== AccountStatus.ACTIVE) throw new NotFoundException('User profile not found.');
    return record;
  }

  async updateMine(user: AuthenticatedUser, dto: UpdateUserProfileDto) {
    const data: Prisma.UserUpdateInput = {};
    if (dto.displayName !== undefined) data.displayName = dto.displayName.trim();
    if (dto.phoneNumber !== undefined) data.phoneNumber = dto.phoneNumber;
    if (!Object.keys(data).length) throw new ConflictException('Provide at least one profile field to update.');

    try {
      const updated = await this.prisma.user.update({
        where: { id: user.id },
        data,
        select: USER_PROFILE_SELECT,
      });
      if (updated.status !== AccountStatus.ACTIVE) throw new ForbiddenException('This account is not active.');
      return updated;
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new ConflictException('An account already uses this phone number.');
      }
      throw error;
    }
  }

  async listUsers(limit = 50) {
    return this.prisma.user.findMany({
      take: Math.min(Math.max(limit, 1), 100),
      orderBy: { createdAt: 'desc' },
      select: USER_PROFILE_SELECT,
    });
  }

  async setRole(admin: AuthenticatedUser | string, userId: string, role: UserRole) {
    if (role === UserRole.DONOR) role = UserRole.INDIVIDUAL;
    const actorId = typeof admin === 'string' ? admin : admin.id;
    return this.prisma.$transaction(
      async (tx) => {
        const target = await tx.user.findUnique({
          where: { id: userId },
          select: USER_PROFILE_SELECT,
        });
        if (!target) throw new NotFoundException('User account not found.');
        if (
          target.role === UserRole.ADMIN &&
          target.status === AccountStatus.ACTIVE &&
          role !== UserRole.ADMIN
        ) {
          const activeAdmins = await tx.user.count({
            where: { role: UserRole.ADMIN, status: AccountStatus.ACTIVE },
          });
          if (activeAdmins <= 1) {
            throw new ConflictException('The last active administrator cannot be demoted.');
          }
        }
        if (target.role === role) return target;

        const updated = await tx.user.update({
          where: { id: userId },
          data: { role },
          select: USER_PROFILE_SELECT,
        });
        if (role === UserRole.HEALTH_WORKER) {
          await tx.healthWorkerProfile.upsert({
            where: { userId },
            create: { userId, verificationStatus: 'PENDING', facilityId: null },
            update:
              target.role === UserRole.HEALTH_WORKER
                ? {}
                : {
                    facilityId: null,
                    verificationStatus: 'PENDING',
                    verifiedAt: null,
                  },
          });
        }
        if (
          target.role === UserRole.HEALTH_WORKER &&
          role !== UserRole.HEALTH_WORKER
        ) {
          await tx.healthWorkerProfile.updateMany({
            where: { userId },
            data: {
              facilityId: null,
              verificationStatus: 'PENDING',
              verifiedAt: null,
            },
          });
        }
        await tx.refreshToken.updateMany({
          where: { userId, revokedAt: null },
          data: { revokedAt: new Date() },
        });
        await tx.auditLog.create({
          data: {
            actorUserId: actorId,
            action: 'USER_ROLE_CHANGED',
            entityType: 'User',
            entityId: userId,
            metadata: { previousRole: target.role, role },
          },
        });
        return updated;
      },
      { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
    );
  }

  async updateAccountStatus(admin: AuthenticatedUser, userId: string, dto: UpdateAccountStatusDto) {
    if (admin.id === userId && dto.status !== AccountStatus.ACTIVE) {
      throw new ConflictException('Administrators cannot suspend or deactivate their own account.');
    }
    return this.prisma.$transaction(
      async (tx) => {
        const target = await tx.user.findUnique({
          where: { id: userId },
          select: { id: true, role: true, status: true },
        });
        if (!target) throw new NotFoundException('User account not found.');
        if (target.role === UserRole.ADMIN && dto.status !== AccountStatus.ACTIVE) {
          const activeAdmins = await tx.user.count({
            where: { role: UserRole.ADMIN, status: AccountStatus.ACTIVE },
          });
          if (activeAdmins <= 1) {
            throw new ConflictException('The last active administrator cannot be suspended or deactivated.');
          }
        }
        const updated = await tx.user.update({
          where: { id: userId },
          data: { status: dto.status },
          select: USER_PROFILE_SELECT,
        });
        if (dto.status !== AccountStatus.ACTIVE) {
          await tx.refreshToken.updateMany({
            where: { userId, revokedAt: null },
            data: { revokedAt: new Date() },
          });
        }
        await tx.auditLog.create({
          data: {
            actorUserId: admin.id,
            action: 'USER_ACCOUNT_STATUS_CHANGED',
            entityType: 'User',
            entityId: userId,
            metadata: { fromStatus: target.status, toStatus: dto.status },
          },
        });
        return updated;
      },
      { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
    );
  }

  setStatus(adminId: string, userId: string, status: AccountStatus) {
    return this.updateAccountStatus(
      {
        id: adminId,
        role: UserRole.ADMIN,
        status: AccountStatus.ACTIVE,
      },
      userId,
      { status },
    );
  }
}
