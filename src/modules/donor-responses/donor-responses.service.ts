import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  BloodGroup,
  DonorResponseStatus,
  EmergencyRequestStatus,
  Prisma,
  RequestActivityType,
  UserRole,
} from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { canDonateTo } from '../donors/blood-compatibility';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';

@Injectable()
export class DonorResponsesService {
  constructor(private readonly prisma: PrismaService) {}

  async respond(user: AuthenticatedUser, requestId: string, dto: CreateDonorResponseDto) {
    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      include: { donorProfile: true },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.DONOR || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    const replay = await this.prisma.donorResponse.findUnique({
      where: { idempotencyKey: dto.idempotencyKey },
    });
    if (replay) return this.assertSameResponse(replay, user.id, requestId, dto.status);
    if (!donor.donorProfile.isAvailable || !donor.donorProfile.bloodGroup) {
      throw new ForbiddenException('Your donor profile is currently unavailable.');
    }

    const request = await this.prisma.emergencyRequest.findUnique({ where: { id: requestId } });
    if (!request) throw new NotFoundException('Emergency request not found.');
    if (!canDonateTo(donor.donorProfile.bloodGroup, request.requestedBloodGroup as BloodGroup)) {
      throw new ForbiddenException('Your blood group is not compatible with this request.');
    }
    const now = new Date();
    if (
      ![EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.WAITING].some((status) => status === request.status) ||
      (request.expiresAt && request.expiresAt <= now)
    ) {
      throw new ConflictException('This emergency request is no longer accepting responses.');
    }

    const priorResponse = await this.prisma.donorResponse.findUnique({
      where: {
        requestId_donorProfileId: {
          requestId,
          donorProfileId: donor.donorProfile.id,
        },
      },
    });
    if (priorResponse) {
      if (
        priorResponse.donorUserId === user.id &&
        priorResponse.idempotencyKey === dto.idempotencyKey &&
        priorResponse.status === dto.status
      ) {
        return priorResponse;
      }
      throw new ConflictException('You have already responded to this emergency request.');
    }

    try {
      return await this.prisma.$transaction(async (tx) => {
        const response = await tx.donorResponse.create({
          data: {
            idempotencyKey: dto.idempotencyKey,
            requestId,
            donorProfileId: donor.donorProfile!.id,
            donorUserId: user.id,
            status: dto.status,
            respondedAt: now,
          },
        });
        await tx.requestActivity.create({
          data: {
            requestId,
            actorUserId: user.id,
            eventType: RequestActivityType.DONOR_RESPONDED,
            metadata: { responseId: response.id, response: dto.status },
          },
        });
        if (dto.status === DonorResponseStatus.ACCEPTED) {
          const changed = await tx.emergencyRequest.updateMany({
            where: {
              id: requestId,
              status: request.status,
              OR: [{ expiresAt: null }, { expiresAt: { gt: now } }],
            },
            data: { status: EmergencyRequestStatus.ACCEPTED },
          });
          if (!changed.count) {
            throw new ConflictException('This emergency request is no longer accepting responses.');
          }
          await tx.requestActivity.create({
            data: {
              requestId,
              actorUserId: user.id,
              eventType: RequestActivityType.STATUS_CHANGED,
              fromStatus: request.status,
              toStatus: EmergencyRequestStatus.ACCEPTED,
              metadata: { cause: 'donor_response', responseId: response.id },
            },
          });
        }
        return response;
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        const duplicate = await this.prisma.donorResponse.findUnique({
          where: { idempotencyKey: dto.idempotencyKey },
        });
        if (duplicate) return this.assertSameResponse(duplicate, user.id, requestId, dto.status);
        throw new ConflictException('You have already responded to this emergency request.');
      }
      throw error;
    }
  }

  async listMine(user: AuthenticatedUser) {
    const donor = await this.prisma.user.findUnique({
      where: { id: user.id },
      select: { id: true, role: true, status: true, donorProfile: { select: { id: true } } },
    });
    if (!donor || donor.status !== 'ACTIVE' || donor.role !== UserRole.DONOR || !donor.donorProfile) {
      throw new ForbiddenException('An active donor profile is required.');
    }
    return this.prisma.donorResponse.findMany({
      where: { donorUserId: user.id },
      include: { request: { include: { facility: true } } },
      orderBy: { createdAt: 'desc' },
    });
  }

  private assertSameResponse(
    existing: { id: string; donorUserId: string; requestId: string; status: DonorResponseStatus },
    userId: string,
    requestId: string,
    status: DonorResponseStatus,
  ) {
    if (existing.donorUserId !== userId || existing.requestId !== requestId || existing.status !== status) {
      throw new ConflictException('This idempotency key was already used for different response data.');
    }
    return existing;
  }
}
