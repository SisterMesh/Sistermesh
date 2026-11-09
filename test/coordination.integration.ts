import { BloodGroup, DonorResponseStatus, EmergencyRequestStatus, RequestUrgency, UserRole } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { AuthenticatedUser } from '../src/common/authenticated-user';
import { DonationConfirmationsService } from '../src/modules/donation-confirmations/donation-confirmations.service';
import { DonorResponsesService } from '../src/modules/donor-responses/donor-responses.service';
import { EmergencyRequestsService } from '../src/modules/emergency-requests/emergency-requests.service';
import { PrismaService } from '../src/modules/prisma/prisma.service';

jest.setTimeout(120_000);

describe('Emergency coordination database integration', () => {
  const prisma = new PrismaService();
  const createdRequestIds: string[] = [];
  const createdUserIds: string[] = [];
  const createdFacilityIds: string[] = [];
  let facilityId: string;
  let worker: AuthenticatedUser;
  let unrelatedWorker: AuthenticatedUser;
  let donors: Array<{ user: AuthenticatedUser; profileId: string }>;
  let requests: EmergencyRequestsService;
  let responses: DonorResponsesService;
  let confirmations: DonationConfirmationsService;

  beforeAll(async () => {
    await prisma.$connect();
    const facility = await prisma.facility.create({
      data: { name: `Phase 3 integration ${randomUUID()}`, status: 'ACTIVE' },
    });
    facilityId = facility.id;
    createdFacilityIds.push(facility.id);

    const workerRow = await prisma.user.create({
      data: {
        role: UserRole.HEALTH_WORKER,
        displayName: 'Integration Worker',
        phoneNumber: `+${randomUUID().replaceAll('-', '').slice(0, 14)}`,
        healthWorkerProfile: {
          create: { facilityId, verificationStatus: 'VERIFIED' },
        },
      },
    });
    createdUserIds.push(workerRow.id);
    worker = { id: workerRow.id, role: UserRole.HEALTH_WORKER, status: 'ACTIVE' };

    const otherFacility = await prisma.facility.create({
      data: { name: `Unrelated integration facility ${randomUUID()}`, status: 'ACTIVE' },
    });
    createdFacilityIds.push(otherFacility.id);
    const otherWorker = await prisma.user.create({
      data: {
        role: UserRole.HEALTH_WORKER,
        displayName: 'Unrelated Integration Worker',
        phoneNumber: `+${randomUUID().replaceAll('-', '').slice(0, 14)}`,
        healthWorkerProfile: {
          create: { facilityId: otherFacility.id, verificationStatus: 'VERIFIED' },
        },
      },
    });
    createdUserIds.push(otherWorker.id);
    unrelatedWorker = { id: otherWorker.id, role: UserRole.HEALTH_WORKER, status: 'ACTIVE' };

    donors = [];
    for (let index = 0; index < 3; index += 1) {
      const donorRow = await prisma.user.create({
        data: {
          role: UserRole.INDIVIDUAL,
          displayName: `Integration Donor ${index + 1}`,
          phoneNumber: `+${randomUUID().replaceAll('-', '').slice(0, 14)}`,
          donorProfile: {
            create: {
              bloodGroup: BloodGroup.O_NEGATIVE,
              isAvailable: true,
              isProfileComplete: true,
            },
          },
        },
        include: { donorProfile: true },
      });
      createdUserIds.push(donorRow.id);
      if (!donorRow.donorProfile) throw new Error('Integration donor profile was not created.');
      donors.push({
        user: { id: donorRow.id, role: UserRole.INDIVIDUAL, status: 'ACTIVE' },
        profileId: donorRow.donorProfile.id,
      });
    }

    requests = new EmergencyRequestsService(prisma);
    responses = new DonorResponsesService(prisma);
    confirmations = new DonationConfirmationsService(prisma);
  });

  afterAll(async () => {
    if (createdRequestIds.length) {
      await prisma.donationConfirmation.deleteMany({
        where: { requestId: { in: createdRequestIds } },
      });
      await prisma.emergencyRequest.deleteMany({
        where: { id: { in: createdRequestIds } },
      });
    }
    if (createdUserIds.length) {
      await prisma.user.deleteMany({ where: { id: { in: createdUserIds } } });
    }
    if (createdFacilityIds.length) {
      await prisma.facility.deleteMany({ where: { id: { in: createdFacilityIds } } });
    }
    await prisma.$disconnect();
  });

  async function createRequest(units: number, expiresAt?: Date) {
    const request = await requests.create(worker, {
      clientRequestId: randomUUID(),
      requestedBloodGroup: BloodGroup.O_NEGATIVE,
      urgency: RequestUrgency.URGENT,
      units,
      ...(expiresAt ? { expiresAt: expiresAt.toISOString() } : {}),
    });
    createdRequestIds.push(request.id);
    return request;
  }

  function operationKey() {
    return randomUUID();
  }

  it('supports multiple donor acceptances and fulfils only after the required confirmations', async () => {
    const request = await createRequest(2);
    await requests.update(worker, request.id, { urgency: RequestUrgency.CRITICAL });
    expect(
      (await requests.listForFacility(worker)).find((row) => row.id === request.id)?.urgency,
    ).toBe(RequestUrgency.CRITICAL);
    const firstKey = operationKey();
    const duplicateSubmissions = await Promise.all([
      responses.respond(donors[0].user, request.id, {
        idempotencyKey: firstKey,
        status: DonorResponseStatus.ACCEPTED,
      }),
      responses.respond(donors[0].user, request.id, {
        idempotencyKey: firstKey,
        status: DonorResponseStatus.ACCEPTED,
      }),
    ]);
    expect(duplicateSubmissions[0].id).toBe(duplicateSubmissions[1].id);

    await responses.respond(donors[1].user, request.id, {
      idempotencyKey: operationKey(),
      status: DonorResponseStatus.ACCEPTED,
    });
    expect(await responses.listMine(donors[0].user)).toHaveLength(1);
    const facilityResponses = await responses.listForRequest(worker, request.id);
    expect(facilityResponses).toHaveLength(2);
    expect(facilityResponses[0]).not.toHaveProperty('donorUser');
    expect(facilityResponses[0]).not.toHaveProperty('phoneNumber');
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.OPEN,
    );

    const firstConfirmationKey = operationKey();
    const firstConfirmation = await confirmations.confirm(worker, request.id, {
      idempotencyKey: firstConfirmationKey,
      donorProfileId: donors[0].profileId,
      receiptHash: '1'.repeat(64),
    });
    const confirmationReplay = await confirmations.confirm(worker, request.id, {
      idempotencyKey: firstConfirmationKey,
      donorProfileId: donors[0].profileId,
      receiptHash: '1'.repeat(64),
    });
    expect(confirmationReplay.id).toBe(firstConfirmation.id);
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.PARTIALLY_FILLED,
    );

    await confirmations.confirm(worker, request.id, {
      idempotencyKey: operationKey(),
      donorProfileId: donors[1].profileId,
      receiptHash: '2'.repeat(64),
    });
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.FULFILLED,
    );
    await expect(requests.cancel(worker, request.id)).rejects.toThrow(
      'Cannot cancel a request in FULFILLED status.',
    );
    await expect(responses.listForRequest(unrelatedWorker, request.id)).rejects.toThrow(
      'Emergency request not found.',
    );
    expect(await prisma.donationConfirmation.count({ where: { requestId: request.id } })).toBe(2);
    const history = await requests.history(worker, request.id);
    expect(history.map((event) => event.eventType)).toEqual(
      expect.arrayContaining(['CREATED', 'DONOR_ACCEPTED', 'DONATION_CONFIRMED', 'PARTIALLY_FILLED', 'FULFILLED']),
    );
    expect(JSON.stringify(history)).not.toContain('receiptHash');
    await expect(
      responses.respond(donors[2].user, request.id, {
        idempotencyKey: operationKey(),
        status: DonorResponseStatus.ACCEPTED,
      }),
    ).rejects.toThrow('This emergency request is no longer accepting responses.');
  });

  it('serializes concurrent confirmations so the confirmed count cannot exceed the target', async () => {
    const request = await createRequest(1);
    for (const donor of donors.slice(0, 2)) {
      await responses.respond(donor.user, request.id, {
        idempotencyKey: operationKey(),
        status: DonorResponseStatus.ACCEPTED,
      });
    }
    const outcomes = await Promise.allSettled(
      donors.slice(0, 2).map((donor, index) =>
        confirmations.confirm(worker, request.id, {
          idempotencyKey: operationKey(),
          donorProfileId: donor.profileId,
          receiptHash: String(index + 3).repeat(64),
        }),
      ),
    );

    expect(outcomes.filter((outcome) => outcome.status === 'fulfilled')).toHaveLength(1);
    expect(await prisma.donationConfirmation.count({ where: { requestId: request.id } })).toBe(1);
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.FULFILLED,
    );
  });

  it('serializes cancellation against a concurrent donor response', async () => {
    const request = await createRequest(2);
    const outcomes = await Promise.allSettled([
      responses.respond(donors[2].user, request.id, {
        idempotencyKey: operationKey(),
        status: DonorResponseStatus.ACCEPTED,
      }),
      requests.cancel(worker, request.id),
    ]);

    expect(outcomes[1].status).toBe('fulfilled');
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.CANCELLED,
    );
    expect(
      await prisma.requestActivity.count({
        where: { requestId: request.id, eventType: 'CANCELLED' },
      }),
    ).toBe(1);
    await expect(requests.cancel(worker, request.id)).resolves.toMatchObject({ id: request.id, status: 'CANCELLED' });
    expect(
      await prisma.requestActivity.count({
        where: { requestId: request.id, eventType: 'CANCELLED' },
      }),
    ).toBe(1);
    const response = await prisma.donorResponse.findUnique({
      where: { requestId_donorProfileId: { requestId: request.id, donorProfileId: donors[2].profileId } },
    });
    if (response) {
      expect(outcomes[0].status).toBe('fulfilled');
      expect(response.status).toBe(DonorResponseStatus.ACCEPTED);
      const cancellation = await prisma.requestActivity.findFirstOrThrow({
        where: { requestId: request.id, eventType: 'CANCELLED' },
      });
      expect(response.createdAt.getTime()).toBeLessThanOrEqual(cancellation.occurredAt.getTime());
    } else {
      expect(outcomes[0].status).toBe('rejected');
    }
  });

  it('expires due requests and records the transition once', async () => {
    const request = await createRequest(1, new Date(Date.now() + 60_000));
    await prisma.emergencyRequest.update({
      where: { id: request.id },
      data: { expiresAt: new Date(Date.now() - 1_000) },
    });

    await expect(requests.expireDueRequests()).resolves.toBeGreaterThanOrEqual(1);
    expect((await prisma.emergencyRequest.findUniqueOrThrow({ where: { id: request.id } })).status).toBe(
      EmergencyRequestStatus.EXPIRED,
    );
    expect(
      await prisma.requestActivity.count({
        where: { requestId: request.id, eventType: 'EXPIRED' },
      }),
    ).toBe(1);
    await expect(
      responses.respond(donors[0].user, request.id, {
        idempotencyKey: operationKey(),
        status: DonorResponseStatus.ACCEPTED,
      }),
    ).rejects.toThrow('This emergency request is no longer accepting responses.');
    await expect(requests.cancel(worker, request.id)).rejects.toThrow(
      'Cannot cancel a request in EXPIRED status.',
    );
  });

  it('supports idempotent donor withdrawal and refuses a second distinct withdrawal', async () => {
    const request = await createRequest(2);
    await responses.respond(donors[2].user, request.id, {
      idempotencyKey: operationKey(),
      status: DonorResponseStatus.ACCEPTED,
    });
    const withdrawalKey = operationKey();
    const first = await responses.withdraw(donors[2].user, request.id, { idempotencyKey: withdrawalKey });
    const replay = await responses.withdraw(donors[2].user, request.id, { idempotencyKey: withdrawalKey });
    expect(replay.id).toBe(first.id);
    expect(replay.status).toBe(DonorResponseStatus.WITHDRAWN);
    await expect(
      responses.withdraw(donors[2].user, request.id, { idempotencyKey: operationKey() }),
    ).rejects.toThrow('This response has already been withdrawn.');
    expect(
      await prisma.requestActivity.count({
        where: { requestId: request.id, eventType: 'DONOR_WITHDRAWN' },
      }),
    ).toBe(1);
  });
});
