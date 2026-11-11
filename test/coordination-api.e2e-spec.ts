import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { UserRole } from "@prisma/client";
import request from "supertest";
import { AuthenticatedUser } from "../src/common/authenticated-user";
import { RolesGuard } from "../src/common/roles.guard";
import { AuthModule } from "../src/modules/auth/auth.module";
import { AccessTokenService } from "../src/modules/auth/access-token.service";
import { DonorResponsesController } from "../src/modules/donor-responses/donor-responses.controller";
import { DonorResponsesService } from "../src/modules/donor-responses/donor-responses.service";
import { EmergencyRequestsController } from "../src/modules/emergency-requests/emergency-requests.controller";
import { EmergencyRequestsService } from "../src/modules/emergency-requests/emergency-requests.service";
import { OfflineSyncController } from "../src/modules/offline-sync/offline-sync.controller";
import { OfflineSyncService } from "../src/modules/offline-sync/offline-sync.service";
import { PrismaModule } from "../src/modules/prisma/prisma.module";
import { PrismaService } from "../src/modules/prisma/prisma.service";

describe("coordination API with verified JWT authentication", () => {
  let app: INestApplication;
  let accessTokens: AccessTokenService;
  const originalSessionSecret = process.env.SESSION_SECRET;
  const actors = new Map<string, AuthenticatedUser>();
  const prismaMock = {
    user: {
      findUnique: jest.fn(),
    },
  };
  const emergencyRequests = {
    create: jest.fn(),
    listEligible: jest.fn(),
    cancel: jest.fn(),
  };
  const donorResponses = {
    respond: jest.fn(),
    listMine: jest.fn(),
    withdraw: jest.fn(),
  };
  const offlineSync = {
    registerDevice: jest.fn(),
    listDevices: jest.fn(),
    revokeDevice: jest.fn(),
    uploadBatch: jest.fn(),
    getChanges: jest.fn(),
    acknowledgeCheckpoint: jest.fn(),
    listConflicts: jest.fn(),
  };

  beforeAll(async () => {
    process.env.SESSION_SECRET =
      "test-only-session-secret-with-more-than-32-bytes";
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id: string } }) =>
        actors.get(args.where.id) ?? null,
    );
    const module = await Test.createTestingModule({
      imports: [PrismaModule, AuthModule],
      controllers: [
        EmergencyRequestsController,
        DonorResponsesController,
        OfflineSyncController,
      ],
      providers: [
        RolesGuard,
        { provide: EmergencyRequestsService, useValue: emergencyRequests },
        { provide: DonorResponsesService, useValue: donorResponses },
        { provide: OfflineSyncService, useValue: offlineSync },
      ],
    })
      .overrideProvider(PrismaService)
      .useValue(prismaMock)
      .compile();
    app = module.createNestApplication();
    app.setGlobalPrefix("api");
    app.useGlobalPipes(
      new ValidationPipe({
        whitelist: true,
        forbidNonWhitelisted: true,
        transform: true,
      }),
    );
    await app.init();
    accessTokens = module.get<AccessTokenService>(AccessTokenService);
  });

  beforeEach(() => {
    jest.clearAllMocks();
    actors.clear();
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id: string } }) =>
        actors.get(args.where.id) ?? null,
    );
  });

  afterAll(async () => {
    await app.close();
    if (originalSessionSecret === undefined) delete process.env.SESSION_SECRET;
    else process.env.SESSION_SECRET = originalSessionSecret;
  });

  async function bearer(
    role: UserRole,
    status: AuthenticatedUser["status"] = "ACTIVE",
  ) {
    const id =
      role === UserRole.INDIVIDUAL
        ? "00000000-0000-4000-8000-000000000002"
        : role === UserRole.HEALTH_WORKER
          ? "00000000-0000-4000-8000-000000000001"
          : "00000000-0000-4000-8000-000000000003";
    actors.set(id, { id, role, status });
    return `Bearer ${await accessTokens.issue(id)}`;
  }

  it("rejects missing or invalid tokens, wrong roles, and invalid request data", async () => {
    const body = {
      clientRequestId: "00000000-0000-4000-8000-000000000011",
      requestedBloodGroup: "O_NEGATIVE",
      urgency: "CRITICAL",
      units: 2,
    };
    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .send(body)
      .expect(401);
    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .set("Authorization", "Bearer not-a-jwt")
      .send(body)
      .expect(401);
    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .set("Authorization", await bearer(UserRole.ADMIN))
      .send(body)
      .expect(403);
    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .set("Authorization", await bearer(UserRole.HEALTH_WORKER, "SUSPENDED"))
      .send(body)
      .expect(401);
    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .set("Authorization", await bearer(UserRole.HEALTH_WORKER))
      .send({ ...body, units: 0 })
      .expect(403);
  });

  it("accepts an individual request with a free-form destination", async () => {
    const created = {
      id: "00000000-0000-4000-8000-000000000010",
      status: "OPEN",
    };
    const body = {
      clientRequestId: "00000000-0000-4000-8000-000000000011",
      requestedBloodGroup: "O_NEGATIVE",
      urgency: "CRITICAL",
      units: 2,
      facilityName: "Community Clinic",
      facilityLocation: "Riverside Road, Uyo",
    };
    emergencyRequests.create.mockResolvedValue(created);

    await request(app.getHttpServer())
      .post("/api/emergency-requests")
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .send(body)
      .expect(201, created);

    expect(emergencyRequests.create).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.INDIVIDUAL }),
      body,
    );
  });

  it("lists compatible requests and accepts a donor response under the donor token", async () => {
    const eligible = [{ id: "request-1", requestedBloodGroup: "O_NEGATIVE" }];
    const response = { id: "response-1", status: "ACCEPTED" };
    emergencyRequests.listEligible.mockResolvedValue(eligible);
    donorResponses.respond.mockResolvedValue(response);

    await request(app.getHttpServer())
      .get("/api/emergency-requests/eligible")
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .expect(200, eligible);
    await request(app.getHttpServer())
      .post("/api/donor-responses/00000000-0000-4000-8000-000000000010")
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .send({
        idempotencyKey: "00000000-0000-4000-8000-000000000012",
        status: "ACCEPTED",
      })
      .expect(201, response);

    expect(donorResponses.respond).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.INDIVIDUAL }),
      "00000000-0000-4000-8000-000000000010",
      {
        idempotencyKey: "00000000-0000-4000-8000-000000000012",
        status: "ACCEPTED",
      },
    );
  });

  it("does not expose facility response lists and supports donor withdrawal retries", async () => {
    const requestId = "00000000-0000-4000-8000-000000000020";
    const withdrawn = { id: "response-1", status: "WITHDRAWN" };
    donorResponses.withdraw.mockResolvedValue(withdrawn);

    await request(app.getHttpServer())
      .get(`/api/donor-responses/requests/${requestId}`)
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .expect(404);
    await request(app.getHttpServer())
      .post(`/api/donor-responses/${requestId}/withdraw`)
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .send({ idempotencyKey: "00000000-0000-4000-8000-000000000021" })
      .expect(201, withdrawn);

    expect(donorResponses.withdraw).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.INDIVIDUAL }),
      requestId,
      { idempotencyKey: "00000000-0000-4000-8000-000000000021" },
    );
  });

  it("protects sync routes with JWT roles and never accepts a client-supplied identity", async () => {
    const device = { deviceId: "00000000-0000-4000-8000-000000000031" };
    offlineSync.registerDevice.mockResolvedValue(device);
    offlineSync.uploadBatch.mockResolvedValue({
      status: "COMPLETED",
      events: [],
    });
    const token = await bearer(UserRole.INDIVIDUAL);

    await request(app.getHttpServer())
      .post("/api/sync/devices")
      .set("Authorization", token)
      .send({
        clientDeviceId: "00000000-0000-4000-8000-000000000032",
        userId: "forged",
      })
      .expect(400);
    await request(app.getHttpServer())
      .post("/api/sync/devices")
      .set("Authorization", token)
      .send({ clientDeviceId: "00000000-0000-4000-8000-000000000032" })
      .expect(201, device);

    const batch = {
      clientBatchId: "00000000-0000-4000-8000-000000000033",
      events: [],
    };
    await request(app.getHttpServer())
      .post(`/api/sync/devices/${device.deviceId}/batches`)
      .set("Authorization", await bearer(UserRole.ADMIN))
      .send(batch)
      .expect(403);
    await request(app.getHttpServer())
      .post(`/api/sync/devices/${device.deviceId}/batches`)
      .set("Authorization", token)
      .send(batch)
      .expect(400);
    expect(offlineSync.registerDevice).toHaveBeenCalledTimes(1);
    expect(offlineSync.registerDevice).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.INDIVIDUAL }),
      { clientDeviceId: "00000000-0000-4000-8000-000000000032" },
    );
    expect(offlineSync.uploadBatch).not.toHaveBeenCalled();
  });
});
