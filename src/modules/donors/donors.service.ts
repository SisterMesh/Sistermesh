import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { AccountStatus, Prisma, UserRole } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonorProfileDto } from './dto/create-donor-profile.dto';
import { UpdateDonorProfileDto } from './dto/update-donor-profile.dto';

@Injectable()
export class DonorsService {
  constructor(private readonly prisma: PrismaService) {}

  async createMine(user: AuthenticatedUser, dto: CreateDonorProfileDto) {
    await this.assertDonor(user.id);
    const locality = dto.locality?.trim() || null;
    try {
      return await this.prisma.donorProfile.create({
        data: {
          userId: user.id,
          bloodGroup: dto.bloodGroup,
          locality,
          isProfileComplete: Boolean(dto.bloodGroup && locality),
        },
        select: this.profileSelect(),
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new ConflictException('A donor profile already exists for this account.');
      }
      throw error;
    }
  }

  async getMine(user: AuthenticatedUser) {
    await this.assertDonor(user.id);
    const profile = await this.prisma.donorProfile.findUnique({
      where: { userId: user.id },
      select: this.profileSelect(),
    });
    if (!profile) throw new NotFoundException('Donor profile not found.');
    return profile;
  }

  async updateMine(user: AuthenticatedUser, dto: UpdateDonorProfileDto) {
    await this.assertDonor(user.id);
    const current = await this.prisma.donorProfile.findUnique({ where: { userId: user.id } });
    if (!current) throw new NotFoundException('Donor profile not found.');
    const locality = dto.locality === undefined ? current.locality : dto.locality?.trim() || null;
    return this.prisma.donorProfile.update({
      where: { userId: user.id },
      data: {
        ...(dto.bloodGroup !== undefined ? { bloodGroup: dto.bloodGroup } : {}),
        ...(dto.locality !== undefined ? { locality } : {}),
        ...(dto.isAvailable !== undefined ? { isAvailable: dto.isAvailable } : {}),
        isProfileComplete: Boolean(dto.bloodGroup ?? current.bloodGroup) && Boolean(locality),
      },
      select: this.profileSelect(),
    });
  }

  private async assertDonor(userId: string) {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { id: true, role: true, status: true },
    });
    if (!user || user.status !== AccountStatus.ACTIVE || user.role !== UserRole.DONOR) {
      throw new ForbiddenException('An active donor account is required.');
    }
  }

  private profileSelect() {
    return {
      id: true,
      userId: true,
      bloodGroup: true,
      isAvailable: true,
      locality: true,
      isProfileComplete: true,
      lastActiveAt: true,
      consentedAt: true,
      createdAt: true,
      updatedAt: true,
      user: { select: { displayName: true, phoneNumber: true } },
    };
  }
}
