import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { AccountStatus, Prisma, UserRole } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { UpdateUserDto } from './dto/update-user.dto';

const safeUserSelect = {
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

  async updateOwnProfile(userId: string, dto: UpdateUserDto) {
    try {
      return await this.prisma.user.update({
        where: { id: userId },
        data: {
          ...(dto.displayName !== undefined && {
            displayName: dto.displayName.trim(),
          }),
          ...(dto.phoneNumber !== undefined && {
            phoneNumber: dto.phoneNumber,
          }),
        },
        select: safeUserSelect,
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('That phone number is already in use.');
      }
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2025'
      ) {
        throw new NotFoundException('Account not found.');
      }
      throw error;
    }
  }

  async listUsers(limit = 50) {
    return this.prisma.user.findMany({
      take: Math.min(Math.max(limit, 1), 100),
      orderBy: { createdAt: 'desc' },
      select: safeUserSelect,
    });
  }

  async setRole(actorId: string, targetId: string, role: UserRole) {
    return this.adminTransaction(async (tx) => {
      const target = await tx.user.findUnique({
        where: { id: targetId },
        select: { id: true, role: true, status: true },
      });
      if (!target) throw new NotFoundException('Account not found.');

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

      const updated = await tx.user.update({
        where: { id: targetId },
        data: { role },
        select: safeUserSelect,
      });
      if (role === UserRole.DONOR) {
        await tx.donorProfile.upsert({
          where: { userId: targetId },
          create: { userId: targetId },
          update: {},
        });
      } else if (role === UserRole.HEALTH_WORKER) {
        await tx.healthWorkerProfile.upsert({
          where: { userId: targetId },
          create: { userId: targetId },
          update:
            target.role === UserRole.HEALTH_WORKER
              ? {}
              : {
                  facilityId: null,
                  verificationStatus: 'PENDING',
                },
        });
      }
      if (
        target.role === UserRole.HEALTH_WORKER &&
        role !== UserRole.HEALTH_WORKER
      ) {
        await tx.healthWorkerProfile.updateMany({
          where: { userId: targetId },
          data: {
            facilityId: null,
            verificationStatus: 'PENDING',
          },
        });
      }
      await tx.auditLog.create({
        data: {
          actorUserId: actorId,
          action: 'USER_ROLE_CHANGED',
          entityType: 'User',
          entityId: targetId,
          metadata: { previousRole: target.role, role },
        },
      });
      return updated;
    });
  }

  async setStatus(actorId: string, targetId: string, status: AccountStatus) {
    return this.adminTransaction(async (tx) => {
      const target = await tx.user.findUnique({
        where: { id: targetId },
        select: { id: true, role: true, status: true },
      });
      if (!target) throw new NotFoundException('Account not found.');

      if (
        target.role === UserRole.ADMIN &&
        target.status === AccountStatus.ACTIVE &&
        status !== AccountStatus.ACTIVE
      ) {
        const activeAdmins = await tx.user.count({
          where: { role: UserRole.ADMIN, status: AccountStatus.ACTIVE },
        });
        if (activeAdmins <= 1) {
          throw new ConflictException('The last active administrator cannot be disabled.');
        }
      }

      const updated = await tx.user.update({
        where: { id: targetId },
        data: { status },
        select: safeUserSelect,
      });
      if (status !== AccountStatus.ACTIVE) {
        await tx.refreshToken.updateMany({
          where: { userId: targetId, revokedAt: null },
          data: { revokedAt: new Date() },
        });
      }
      await tx.auditLog.create({
        data: {
          actorUserId: actorId,
          action: 'USER_STATUS_CHANGED',
          entityType: 'User',
          entityId: targetId,
          metadata: { previousStatus: target.status, status },
        },
      });
      return updated;
    });
  }

  private async adminTransaction<T>(
    callback: (tx: Prisma.TransactionClient) => Promise<T>,
  ): Promise<T> {
    try {
      return await this.prisma.$transaction(callback, {
        isolationLevel: Prisma.TransactionIsolationLevel.Serializable,
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2034'
      ) {
        throw new ConflictException(
          'A concurrent administrator update occurred. Retry the operation.',
        );
      }
      throw error;
    }
  }
}
