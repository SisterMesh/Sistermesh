import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { UpdateDonorProfileDto } from './dto/update-donor-profile.dto';

const profileSelect = {
  id: true,
  userId: true,
  bloodGroup: true,
  isAvailable: true,
  isProfileComplete: true,
  locality: true,
  lastActiveAt: true,
  consentedAt: true,
  createdAt: true,
  updatedAt: true,
} satisfies Prisma.DonorProfileSelect;

@Injectable()
export class DonorsService {
  constructor(private readonly prisma: PrismaService) {}

  async getOwnProfile(userId: string) {
    const profile = await this.prisma.donorProfile.findUnique({
      where: { userId },
      select: profileSelect,
    });
    if (!profile) throw new NotFoundException('Donor profile not found.');
    return profile;
  }

  async updateOwnProfile(userId: string, dto: UpdateDonorProfileDto) {
    const existing = await this.prisma.donorProfile.findUnique({
      where: { userId },
      select: { bloodGroup: true, locality: true },
    });
    const bloodGroup =
      dto.bloodGroup === undefined ? existing?.bloodGroup : dto.bloodGroup;
    const locality =
      dto.locality === undefined ? existing?.locality : dto.locality?.trim();
    const isProfileComplete = Boolean(bloodGroup && locality);
    const data = {
      ...(dto.bloodGroup !== undefined && { bloodGroup: dto.bloodGroup }),
      ...(dto.locality !== undefined && {
        locality: dto.locality?.trim() || null,
      }),
      ...(dto.isAvailable !== undefined && { isAvailable: dto.isAvailable }),
      isProfileComplete,
    };

    return this.prisma.donorProfile.upsert({
      where: { userId },
      create: { userId, ...data },
      update: data,
      select: profileSelect,
    });
  }
}
