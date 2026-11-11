import {
  BloodGroup,
  EmergencyRequestStatus,
  RequestUrgency,
  UserRole,
} from "@prisma/client";
import { AuthenticatedUser } from "../../common/authenticated-user";
import { PrismaService } from "../prisma/prisma.service";
import { CreateEmergencyRequestDto } from "./dto/create-emergency-request.dto";
import { EmergencyRequestsService } from "./emergency-requests.service";

describe("EmergencyRequestsService", () => {
  const user: AuthenticatedUser = {
    id: "00000000-0000-4000-8000-000000000001",
    role: UserRole.INDIVIDUAL,
    status: "ACTIVE",
  };

  it("returns an existing request for an identical create retry, even after its expiry", async () => {
    const expiresAt = new Date("2025-01-01T00:00:00.000Z");
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: "00000000-0000-4000-8000-000000000002",
      requestedBloodGroup: BloodGroup.O_NEGATIVE,
      urgency: RequestUrgency.CRITICAL,
      units: 2,
      facilityName: "City Hospital",
      facilityLocation: "Lagos",
      isForSelf: true,
      expiresAt: expiresAt.toISOString(),
    };
    const existing = {
      id: "00000000-0000-4000-8000-000000000003",
      createdByUserId: user.id,
      facilityId: "00000000-0000-4000-8000-000000000004",
      facilityName: dto.facilityName,
      facilityLocation: dto.facilityLocation,
      facilityLatitude: null,
      facilityLongitude: null,
      requestedBloodGroup: dto.requestedBloodGroup,
      urgency: dto.urgency,
      units: dto.units,
      expiresAt,
      isForSelf: true,
      beneficiaryName: null,
      beneficiaryRelationship: null,
      additionalInfo: null,
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: "ACTIVE",
          role: UserRole.INDIVIDUAL,
        }),
      },
      emergencyRequest: {
        findUnique: jest.fn().mockResolvedValue(existing),
        create: jest.fn(),
      },
      facility: { findFirst: jest.fn() },
      $transaction: jest.fn(),
    } as unknown as PrismaService;
    const service = new EmergencyRequestsService(prisma);

    await expect(service.create(user, dto)).resolves.toBe(existing);
    expect(prisma.emergencyRequest.create).not.toHaveBeenCalled();
  });

  it("records a new request and its creation activity in the same transaction", async () => {
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: "00000000-0000-4000-8000-000000000005",
      requestedBloodGroup: BloodGroup.A_POSITIVE,
      urgency: RequestUrgency.URGENT,
      units: 1,
      facilityName: "Community Clinic",
      facilityLocation: "Lagos",
    };
    const record = {
      id: "00000000-0000-4000-8000-000000000006",
      status: EmergencyRequestStatus.OPEN,
    };
    const tx = {
      emergencyRequest: { create: jest.fn().mockResolvedValue(record) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      syncCursorState: { update: jest.fn().mockResolvedValue({ value: 1n }) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          status: "ACTIVE",
          role: UserRole.INDIVIDUAL,
        }),
      },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(null) },
      facility: { findFirst: jest.fn() },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) =>
        callback(tx),
      ),
    } as unknown as PrismaService;
    const service = new EmergencyRequestsService(prisma);

    await expect(service.create(user, dto)).resolves.toBe(record);
    expect(tx.requestActivity.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          eventType: "CREATED",
          requestId: record.id,
        }),
      }),
    );
  });

  it("stores the request destination and keeps beneficiary details private", async () => {
    const requester: AuthenticatedUser = {
      id: "00000000-0000-4000-8000-000000000007",
      role: UserRole.INDIVIDUAL,
      status: "ACTIVE",
    };
    const facilityId = "00000000-0000-4000-8000-000000000008";
    const dto: CreateEmergencyRequestDto = {
      clientRequestId: "00000000-0000-4000-8000-000000000009",
      facilityId,
      facilityName: "Community Clinic",
      facilityLocation: "Lagos",
      requestedBloodGroup: BloodGroup.A_POSITIVE,
      urgency: RequestUrgency.URGENT,
      units: 1,
      isForSelf: false,
      beneficiaryName: "Private beneficiary",
      beneficiaryRelationship: "Relative",
      additionalInfo: "Private coordination note",
    };
    const record = {
      id: "00000000-0000-4000-8000-000000000010",
      status: EmergencyRequestStatus.OPEN,
    };
    const tx = {
      emergencyRequest: { create: jest.fn().mockResolvedValue(record) },
      requestActivity: { create: jest.fn().mockResolvedValue({}) },
      syncCursorState: { update: jest.fn().mockResolvedValue({ value: 1n }) },
    };
    const prisma = {
      user: {
        findUnique: jest.fn().mockResolvedValue({
          id: requester.id,
          status: "ACTIVE",
          role: UserRole.INDIVIDUAL,
        }),
      },
      facility: {
        findFirst: jest.fn().mockResolvedValue({
          id: facilityId, name: "Community Clinic", locality: "Lagos", state: "Lagos",
        }),
      },
      emergencyRequest: { findUnique: jest.fn().mockResolvedValue(null) },
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) =>
        callback(tx),
      ),
    } as unknown as PrismaService;

    const created = await new EmergencyRequestsService(prisma).create(
      requester,
      dto,
    );

    expect(created).toEqual(record);
    expect(tx.emergencyRequest.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          status: EmergencyRequestStatus.OPEN,
          facilityId,
          facilityName: "Community Clinic",
          facilityLocation: "Lagos",
          isForSelf: false,
          beneficiaryName: "Private beneficiary",
          additionalInfo: "Private coordination note",
        }),
      }),
    );
  });

  it("rejects the retired health-worker request review flow", async () => {
    const requestId = "00000000-0000-4000-8000-000000000012";
    const worker: AuthenticatedUser = {
      id: "00000000-0000-4000-8000-000000000013",
      role: UserRole.HEALTH_WORKER,
      status: "ACTIVE",
    };
    await expect(
      new EmergencyRequestsService({} as PrismaService).review(worker, requestId, {
        action: "APPROVE",
        expectedUpdatedAt: new Date().toISOString(),
      }),
    ).rejects.toThrow("Health-worker facility workflows are no longer available.");
  });
});
