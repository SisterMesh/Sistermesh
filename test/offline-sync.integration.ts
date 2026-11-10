import {
  BloodGroup,
  RequestUrgency,
  SyncEventStatus,
  UserRole,
} from "@prisma/client";
import { randomUUID } from "node:crypto";
import { AuthenticatedUser } from "../src/common/authenticated-user";
import { DonationConfirmationsService } from "../src/modules/donation-confirmations/donation-confirmations.service";
import { DonorResponsesService } from "../src/modules/donor-responses/donor-responses.service";
import { EmergencyRequestsService } from "../src/modules/emergency-requests/emergency-requests.service";
import { OfflineSyncService } from "../src/modules/offline-sync/offline-sync.service";
import { PrismaService } from "../src/modules/prisma/prisma.service";

jest.setTimeout(120_000);

describe("Offline synchronization database integration", () => {
  const prisma = new PrismaService();
  const requestIds: string[] = [];
  const userIds: string[] = [];
  const facilityIds: string[] = [];
  let facilityId: string;
  let worker: AuthenticatedUser;
  let donor: AuthenticatedUser;
  let unrelatedWorker: AuthenticatedUser;
  let donorProfileId: string;
  let workerDeviceId: string;
  let workerClientDeviceId: string;
  let donorDeviceId: string;
  let donorClientDeviceId: string;
  let sync: OfflineSyncService;

  beforeAll(async () => {
    await prisma.$connect();
    const facility = await prisma.facility.create({
      data: {
        name: `Offline sync integration ${randomUUID()}`,
        status: "ACTIVE",
      },
    });
    facilityId = facility.id;
    facilityIds.push(facility.id);

    const workerRow = await prisma.user.create({
      data: {
        role: UserRole.HEALTH_WORKER,
        displayName: "Sync Integration Worker",
        phoneNumber: `+${randomUUID().replaceAll("-", "").slice(0, 14)}`,
        healthWorkerProfile: {
          create: { facilityId, verificationStatus: "VERIFIED" },
        },
      },
    });
    userIds.push(workerRow.id);
    worker = {
      id: workerRow.id,
      role: UserRole.HEALTH_WORKER,
      status: "ACTIVE",
    };

    const otherFacility = await prisma.facility.create({
      data: {
        name: `Offline sync unrelated ${randomUUID()}`,
        status: "ACTIVE",
      },
    });
    facilityIds.push(otherFacility.id);
    const otherWorker = await prisma.user.create({
      data: {
        role: UserRole.HEALTH_WORKER,
        displayName: "Unrelated Sync Worker",
        phoneNumber: `+${randomUUID().replaceAll("-", "").slice(0, 14)}`,
        healthWorkerProfile: {
          create: {
            facilityId: otherFacility.id,
            verificationStatus: "VERIFIED",
          },
        },
      },
    });
    userIds.push(otherWorker.id);
    unrelatedWorker = {
      id: otherWorker.id,
      role: UserRole.HEALTH_WORKER,
      status: "ACTIVE",
    };

    const donorRow = await prisma.user.create({
      data: {
        role: UserRole.INDIVIDUAL,
        displayName: "Sync Integration Donor",
        phoneNumber: `+${randomUUID().replaceAll("-", "").slice(0, 14)}`,
        donorProfile: {
          create: {
            bloodGroup: BloodGroup.A_POSITIVE,
            isAvailable: true,
            isProfileComplete: true,
          },
        },
      },
    });
    userIds.push(donorRow.id);
    donor = { id: donorRow.id, role: UserRole.INDIVIDUAL, status: "ACTIVE" };
    donorProfileId = (
      await prisma.donorProfile.findUniqueOrThrow({
        where: { userId: donorRow.id },
      })
    ).id;

    sync = new OfflineSyncService(
      prisma,
      new EmergencyRequestsService(prisma),
      new DonorResponsesService(prisma),
      new DonationConfirmationsService(prisma),
    );

    workerClientDeviceId = randomUUID();
    const workerDevice = await sync.registerDevice(worker, {
      clientDeviceId: workerClientDeviceId,
      name: "Integration worker device",
    });
    workerDeviceId = workerDevice.deviceId;
    donorClientDeviceId = randomUUID();
    const donorDevice = await sync.registerDevice(donor, {
      clientDeviceId: donorClientDeviceId,
      name: "Integration donor device",
    });
    donorDeviceId = donorDevice.deviceId;

    const startingCursor = (
      await prisma.syncCursorState.findUniqueOrThrow({ where: { id: 1 } })
    ).value;
    await prisma.syncCheckpoint.updateMany({
      where: { registeredDeviceId: { in: [workerDeviceId, donorDeviceId] } },
      data: { lastCursor: startingCursor, pendingCursor: null },
    });
  });

  afterAll(async () => {
    const deviceIds = [workerDeviceId, donorDeviceId].filter(Boolean);
    if (deviceIds.length) {
      await prisma.syncConflict.deleteMany({
        where: { registeredDeviceId: { in: deviceIds } },
      });
      await prisma.syncEvent.deleteMany({
        where: { registeredDeviceId: { in: deviceIds } },
      });
      await prisma.syncBatch.deleteMany({
        where: { registeredDeviceId: { in: deviceIds } },
      });
      await prisma.syncCheckpoint.deleteMany({
        where: { registeredDeviceId: { in: deviceIds } },
      });
      await prisma.registeredDevice.deleteMany({
        where: { id: { in: deviceIds } },
      });
    }
    if (requestIds.length) {
      await prisma.requestActivity.deleteMany({
        where: { requestId: { in: requestIds } },
      });
      await prisma.donationConfirmation.deleteMany({
        where: { requestId: { in: requestIds } },
      });
      await prisma.donorResponse.deleteMany({
        where: { requestId: { in: requestIds } },
      });
      await prisma.emergencyRequest.deleteMany({
        where: { id: { in: requestIds } },
      });
    }
    if (userIds.length)
      await prisma.user.deleteMany({ where: { id: { in: userIds } } });
    if (facilityIds.length)
      await prisma.facility.deleteMany({ where: { id: { in: facilityIds } } });
    await prisma.$disconnect();
  });

  it("processes partial batches idempotently, detects conflicts, pages safely, and enforces device ownership", async () => {
    const firstEventId = randomUUID();
    const secondEventId = randomUUID();
    const badEventId = randomUUID();
    const batchId = randomUUID();
    const localCreatedAt = new Date().toISOString();

    const requestEvent = (
      eventId: string,
      requestedBloodGroup: BloodGroup,
    ) => ({
      eventId,
      eventType: "request.create",
      entityId: null,
      originDeviceId: workerClientDeviceId,
      localCreatedAt,
      payloadSchemaVersion: 1,
      payload: {
        requestedBloodGroup,
        urgency: RequestUrgency.URGENT,
        units: 1,
      },
    });
    const approveRequest = async (requestId: string) => {
      const request = await prisma.emergencyRequest.findUniqueOrThrow({
        where: { id: requestId },
      });
      const review = await sync.uploadBatch(worker, workerDeviceId, {
        clientBatchId: randomUUID(),
        events: [
          {
            eventId: randomUUID(),
            eventType: "request.review",
            entityId: requestId,
            originDeviceId: workerClientDeviceId,
            localCreatedAt,
            payloadSchemaVersion: 1,
            payload: {
              action: "APPROVE",
              expectedUpdatedAt: request.updatedAt.toISOString(),
            },
          },
        ],
      });
      expect(review.events[0].status).toBe(SyncEventStatus.PROCESSED);
    };
    const firstCreate = requestEvent(firstEventId, BloodGroup.O_NEGATIVE);
    const secondCreate = requestEvent(secondEventId, BloodGroup.A_POSITIVE);
    const invalidEvent = {
      eventId: badEventId,
      eventType: "request.unsupported",
      entityId: null,
      originDeviceId: workerClientDeviceId,
      localCreatedAt,
      payloadSchemaVersion: 1,
      payload: {},
    };
    const batch = {
      clientBatchId: batchId,
      events: [firstCreate, secondCreate, invalidEvent],
    };

    const firstResult = await sync.uploadBatch(worker, workerDeviceId, batch);
    expect(firstResult.status).toBe("PARTIALLY_COMPLETED");
    expect(firstResult.events.map((event) => event.status)).toEqual([
      SyncEventStatus.PROCESSED,
      SyncEventStatus.PROCESSED,
      SyncEventStatus.REJECTED,
    ]);
    const requestIdsFromResult = firstResult.events
      .filter(
        (event) => event.status === SyncEventStatus.PROCESSED && event.entityId,
      )
      .map((event) => event.entityId!);
    requestIds.push(...requestIdsFromResult);
    expect(
      await prisma.emergencyRequest.count({
        where: { id: { in: requestIdsFromResult } },
      }),
    ).toBe(2);

    const replay = await sync.uploadBatch(worker, workerDeviceId, batch);
    expect(replay.events).toEqual(firstResult.events);
    expect(
      await prisma.emergencyRequest.count({
        where: { id: { in: requestIdsFromResult } },
      }),
    ).toBe(2);

    for (const requestId of requestIdsFromResult)
      await approveRequest(requestId);

    const staleUpdate = {
      eventId: randomUUID(),
      eventType: "request.update",
      entityId: requestIdsFromResult[0],
      originDeviceId: workerClientDeviceId,
      localCreatedAt,
      payloadSchemaVersion: 1,
      payload: {
        urgency: RequestUrgency.CRITICAL,
        expectedUpdatedAt: "2000-01-01T00:00:00.000Z",
      },
    };
    const staleResult = await sync.uploadBatch(worker, workerDeviceId, {
      clientBatchId: randomUUID(),
      events: [staleUpdate],
    });
    expect(staleResult.events[0]).toMatchObject({
      status: SyncEventStatus.CONFLICT,
      errorCode: "VERSION_CONFLICT",
    });
    expect(
      (
        await prisma.emergencyRequest.findUniqueOrThrow({
          where: { id: requestIdsFromResult[0] },
        })
      ).urgency,
    ).toBe(RequestUrgency.URGENT);

    const changedReplay = {
      ...firstCreate,
      payload: { ...firstCreate.payload, units: 3 },
    };
    const eventIdReuse = await sync.uploadBatch(worker, workerDeviceId, {
      clientBatchId: randomUUID(),
      events: [changedReplay],
    });
    expect(eventIdReuse.events[0]).toMatchObject({
      status: SyncEventStatus.CONFLICT,
      errorCode: "EVENT_ID_REUSE",
    });
    expect(
      await prisma.syncConflict.count({
        where: { registeredDeviceId: workerDeviceId },
      }),
    ).toBe(1);

    const firstPage = await sync.getChanges(worker, workerDeviceId, 1);
    expect(firstPage.changes).toHaveLength(1);
    expect(firstPage.hasMore).toBe(true);
    const repeatedPage = await sync.getChanges(worker, workerDeviceId, 1);
    expect(repeatedPage.changes.map((change) => change.cursor)).toEqual(
      firstPage.changes.map((change) => change.cursor),
    );
    await expect(
      sync.acknowledgeCheckpoint(worker, workerDeviceId, {
        cursor: (BigInt(firstPage.nextCursor) + 100n).toString(),
      }),
    ).rejects.toThrow();
    const allOwnerChanges = [];
    let page = firstPage;
    while (true) {
      allOwnerChanges.push(...page.changes);
      await sync.acknowledgeCheckpoint(worker, workerDeviceId, {
        cursor: page.nextCursor,
      });
      if (!page.hasMore) break;
      page = await sync.getChanges(worker, workerDeviceId, 1);
      expect(page.changes).toHaveLength(1);
    }
    expect(allOwnerChanges).toHaveLength(4);
    expect(new Set(allOwnerChanges.map((change) => change.request.id))).toEqual(
      new Set(requestIdsFromResult),
    );
    expect(
      (await sync.getChanges(worker, workerDeviceId, 1)).changes,
    ).toHaveLength(0);

    const donorChanges = await sync.getChanges(donor, donorDeviceId, 100);
    expect(donorChanges.changes).toHaveLength(2);
    expect(
      donorChanges.changes.every(
        (change) => change.request.id === requestIdsFromResult[1],
      ),
    ).toBe(true);
    expect(donorChanges.changes[0].request).not.toHaveProperty(
      "donorResponses",
    );
    expect(donorChanges.changes[0].request).not.toHaveProperty("donorUser");
    expect(donorChanges.changes[0].request).not.toHaveProperty(
      "beneficiaryName",
    );

    const beforeUpdate = await prisma.emergencyRequest.findUniqueOrThrow({
      where: { id: requestIdsFromResult[0] },
    });
    const updateEventId = randomUUID();
    const updateBatch = {
      clientBatchId: randomUUID(),
      events: [
        {
          eventId: updateEventId,
          eventType: "request.update",
          entityId: requestIdsFromResult[0],
          originDeviceId: workerClientDeviceId,
          localCreatedAt,
          payloadSchemaVersion: 1,
          payload: {
            urgency: RequestUrgency.CRITICAL,
            expectedUpdatedAt: beforeUpdate.updatedAt.toISOString(),
          },
        },
      ],
    };
    const updateResult = await sync.uploadBatch(
      worker,
      workerDeviceId,
      updateBatch,
    );
    expect(updateResult.events[0].status).toBe(SyncEventStatus.PROCESSED);
    expect(
      (await sync.uploadBatch(worker, workerDeviceId, updateBatch)).events,
    ).toEqual(updateResult.events);
    expect(
      await prisma.requestActivity.count({
        where: { syncEventId: updateEventId },
      }),
    ).toBe(1);
    expect(
      (
        await prisma.emergencyRequest.findUniqueOrThrow({
          where: { id: requestIdsFromResult[0] },
        })
      ).urgency,
    ).toBe(RequestUrgency.CRITICAL);

    const cancelledCreate = requestEvent(randomUUID(), BloodGroup.A_POSITIVE);
    const cancelledCreation = await sync.uploadBatch(worker, workerDeviceId, {
      clientBatchId: randomUUID(),
      events: [cancelledCreate],
    });
    const cancelledRequestId = cancelledCreation.events[0].entityId!;
    requestIds.push(cancelledRequestId);
    const cancellation = await sync.uploadBatch(worker, workerDeviceId, {
      clientBatchId: randomUUID(),
      events: [
        {
          eventId: randomUUID(),
          eventType: "request.cancel",
          entityId: cancelledRequestId,
          originDeviceId: workerClientDeviceId,
          localCreatedAt,
          payloadSchemaVersion: 1,
          payload: {},
        },
      ],
    });
    expect(cancellation.events[0].status).toBe(SyncEventStatus.PROCESSED);
    const lateResponse = await sync.uploadBatch(donor, donorDeviceId, {
      clientBatchId: randomUUID(),
      events: [
        {
          eventId: randomUUID(),
          eventType: "donor-response.accept",
          entityId: cancelledRequestId,
          originDeviceId: donorClientDeviceId,
          localCreatedAt,
          payloadSchemaVersion: 1,
          payload: {},
        },
      ],
    });
    expect(lateResponse.events[0]).toMatchObject({
      status: SyncEventStatus.CONFLICT,
      errorCode: "REQUEST_CLOSED",
    });

    const confirmationCreate = requestEvent(
      randomUUID(),
      BloodGroup.A_POSITIVE,
    );
    const confirmationCreation = await sync.uploadBatch(
      worker,
      workerDeviceId,
      {
        clientBatchId: randomUUID(),
        events: [confirmationCreate],
      },
    );
    const confirmationRequestId = confirmationCreation.events[0].entityId!;
    requestIds.push(confirmationRequestId);
    await approveRequest(confirmationRequestId);
    const donorAcceptance = await sync.uploadBatch(donor, donorDeviceId, {
      clientBatchId: randomUUID(),
      events: [
        {
          eventId: randomUUID(),
          eventType: "donor-response.accept",
          entityId: confirmationRequestId,
          originDeviceId: donorClientDeviceId,
          localCreatedAt,
          payloadSchemaVersion: 1,
          payload: {},
        },
      ],
    });
    expect(donorAcceptance.events[0].status).toBe(SyncEventStatus.PROCESSED);
    const donationConfirmation = await sync.uploadBatch(
      worker,
      workerDeviceId,
      {
        clientBatchId: randomUUID(),
        events: [
          {
            eventId: randomUUID(),
            eventType: "donation.confirm",
            entityId: confirmationRequestId,
            originDeviceId: workerClientDeviceId,
            localCreatedAt,
            payloadSchemaVersion: 1,
            payload: {
              donorProfileId,
              receiptHash: "a".repeat(64),
            },
          },
        ],
      },
    );
    expect(donationConfirmation.events[0].status).toBe(
      SyncEventStatus.PROCESSED,
    );
    expect(
      (
        await prisma.emergencyRequest.findUniqueOrThrow({
          where: { id: confirmationRequestId },
        })
      ).status,
    ).toBe("FULFILLED");

    expect(
      (await sync.listConflicts(worker, workerDeviceId)).conflicts,
    ).toHaveLength(2);
    expect(
      (await sync.listConflicts(donor, donorDeviceId)).conflicts,
    ).toHaveLength(1);
    await expect(
      sync.getChanges(unrelatedWorker, workerDeviceId),
    ).rejects.toThrow("Registered device not found.");

    await sync.revokeDevice(worker, workerDeviceId);
    await expect(
      sync.uploadBatch(worker, workerDeviceId, batch),
    ).rejects.toThrow("This device has been revoked.");
    await expect(
      sync.registerDevice(worker, { clientDeviceId: workerClientDeviceId }),
    ).rejects.toThrow(
      "This device has been revoked and cannot be reactivated.",
    );
  });
});
