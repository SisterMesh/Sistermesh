import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { UserRole } from "@prisma/client";
import request from "supertest";
import { RolesGuard } from "../src/common/roles.guard";
import { AuthModule } from "../src/modules/auth/auth.module";
import { AccessTokenService } from "../src/modules/auth/access-token.service";
import { AdminFacilitiesController } from "../src/modules/facilities/admin-facilities.controller";
import { FacilitiesService } from "../src/modules/facilities/facilities.service";
import { AdminHealthWorkersController } from "../src/modules/health-workers/admin-health-workers.controller";
import { HealthWorkersService } from "../src/modules/health-workers/health-workers.service";
import { PrismaModule } from "../src/modules/prisma/prisma.module";
import { PrismaService } from "../src/modules/prisma/prisma.service";
import { AdminUsersController } from "../src/modules/users/admin-users.controller";
import { UsersController } from "../src/modules/users/users.controller";
import { UsersService } from "../src/modules/users/users.service";

describe("Phase 2 authentication and access control", () => {
  let app: INestApplication;
  let accessTokens: AccessTokenService;
  const originalSessionSecret = process.env.SESSION_SECRET;
  const actorRecords = new Map<string, Record<string, unknown>>();
  let registeredPasswordHash: string | undefined;
  const safeDonor = {
    id: "00000000-0000-4000-8000-000000000051",
    role: UserRole.INDIVIDUAL,
    status: "ACTIVE",
    displayName: "Sample Donor",
    phoneNumber: "+2348012345678",
    donorProfile: {
      id: "00000000-0000-4000-8000-000000000052",
      bloodGroup: "O_NEGATIVE",
      isAvailable: true,
      locality: "Central",
      isProfileComplete: true,
    },
    healthWorkerProfile: null,
  };
  const prismaMock = {
    user: {
      create: jest.fn(),
      findUnique: jest.fn(),
    },
    refreshToken: {
      create: jest.fn(),
      findUnique: jest.fn(),
      updateMany: jest.fn(),
    },
    healthWorkerProfile: {
      findUnique: jest
        .fn()
        .mockResolvedValue({ verificationStatus: "VERIFIED" }),
    },
  };
  const usersService = {
    getMine: jest.fn(),
    updateMine: jest.fn(),
    updateAccountStatus: jest.fn(),
  };
  const facilitiesService = {
    create: jest.fn(),
    listForAdmin: jest.fn(),
    getForAdmin: jest.fn(),
    listHealthWorkers: jest.fn(),
    update: jest.fn(),
    updateStatus: jest.fn(),
  };
  const healthWorkersService = {
    setVerification: jest.fn(),
    assignFacility: jest.fn(),
    removeFacilityAssignment: jest.fn(),
  };

  beforeAll(async () => {
    process.env.SESSION_SECRET =
      "test-only-session-secret-with-more-than-32-bytes";
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id?: string; phoneNumber?: string } }) => {
        if (
          args.where.phoneNumber === safeDonor.phoneNumber &&
          registeredPasswordHash
        ) {
          return { ...safeDonor, passwordHash: registeredPasswordHash };
        }
        if (args.where.id) return actorRecords.get(args.where.id) ?? null;
        return null;
      },
    );
    const module = await Test.createTestingModule({
      imports: [PrismaModule, AuthModule],
      controllers: [
        UsersController,
        AdminUsersController,
        AdminFacilitiesController,
        AdminHealthWorkersController,
      ],
      providers: [
        RolesGuard,
        { provide: UsersService, useValue: usersService },
        { provide: FacilitiesService, useValue: facilitiesService },
        { provide: HealthWorkersService, useValue: healthWorkersService },
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
    actorRecords.clear();
    registeredPasswordHash = undefined;
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id?: string; phoneNumber?: string } }) => {
        if (
          args.where.phoneNumber === safeDonor.phoneNumber &&
          registeredPasswordHash
        ) {
          return { ...safeDonor, passwordHash: registeredPasswordHash };
        }
        if (args.where.id) return actorRecords.get(args.where.id) ?? null;
        return null;
      },
    );
    prismaMock.healthWorkerProfile.findUnique.mockResolvedValue({
      verificationStatus: "VERIFIED",
    });
  });

  afterAll(async () => {
    await app.close();
    if (originalSessionSecret === undefined) delete process.env.SESSION_SECRET;
    else process.env.SESSION_SECRET = originalSessionSecret;
  });

  async function bearer(role: UserRole) {
    const id =
      role === UserRole.INDIVIDUAL
        ? safeDonor.id
        : role === UserRole.HEALTH_WORKER
          ? "00000000-0000-4000-8000-000000000053"
          : "00000000-0000-4000-8000-000000000054";
    actorRecords.set(id, {
      id,
      role,
      status: "ACTIVE",
      displayName:
        role === UserRole.INDIVIDUAL
          ? safeDonor.displayName
          : "Authenticated User",
      phoneNumber:
        role === UserRole.INDIVIDUAL ? safeDonor.phoneNumber : "+2348012345679",
    });
    return `Bearer ${await accessTokens.issue(id)}`;
  }

  it("registers only a donor role, stores no plain password, and keeps refresh token out of JSON", async () => {
    prismaMock.user.create.mockResolvedValue(safeDonor);
    prismaMock.refreshToken.create.mockResolvedValue({});
    const body = {
      displayName: "Sample Donor",
      phoneNumber: "+2348012345678",
      password: "Correct horse battery staple 24",
      bloodGroup: "O_NEGATIVE",
      locality: "Central",
    };

    await request(app.getHttpServer())
      .post("/api/auth/register/donor")
      .send({ ...body, role: "ADMIN" })
      .expect(400);
    expect(prismaMock.user.create).not.toHaveBeenCalled();

    const response = await request(app.getHttpServer())
      .post("/api/auth/register/donor")
      .send(body)
      .expect(201);
    expect(prismaMock.user.create.mock.calls[0][0].data.role).toBe(
      UserRole.INDIVIDUAL,
    );
    expect(prismaMock.user.create.mock.calls[0][0].data.passwordHash).not.toBe(
      body.password,
    );
    expect(response.body.user.passwordHash).toBeUndefined();
    expect(response.body.refreshToken).toBeUndefined();
    expect(response.headers["set-cookie"][0]).toContain("HttpOnly");
    expect(response.headers["set-cookie"][0]).toContain("Secure");

    registeredPasswordHash = prismaMock.user.create.mock.calls[0][0].data
      .passwordHash as string;
    actorRecords.set(safeDonor.id, safeDonor);
    await request(app.getHttpServer())
      .get("/api/auth/me")
      .set("Authorization", `Bearer ${response.body.accessToken}`)
      .expect(200, safeDonor);
    const login = await request(app.getHttpServer())
      .post("/api/auth/login")
      .send({ phoneNumber: body.phoneNumber, password: body.password })
      .expect(201);
    expect(login.body.accessToken).toBeTruthy();
    expect(login.body.refreshToken).toBeUndefined();
  });

  it("derives user ownership from the verified token and rejects body-supplied identity", async () => {
    usersService.getMine.mockResolvedValue({
      id: safeDonor.id,
      role: UserRole.INDIVIDUAL,
    });
    usersService.updateMine.mockResolvedValue({
      id: safeDonor.id,
      displayName: "Updated Name",
    });
    const authorization = await bearer(UserRole.INDIVIDUAL);

    await request(app.getHttpServer())
      .get("/api/users/me")
      .set("Authorization", authorization)
      .expect(200, { id: safeDonor.id, role: UserRole.INDIVIDUAL });
    await request(app.getHttpServer())
      .patch("/api/users/me")
      .set("Authorization", authorization)
      .send({
        displayName: "Updated Name",
        userId: "00000000-0000-4000-8000-000000000099",
      })
      .expect(400);
    await request(app.getHttpServer())
      .patch("/api/users/me")
      .set("Authorization", authorization)
      .send({ displayName: "Updated Name" })
      .expect(200, { id: safeDonor.id, displayName: "Updated Name" });

    expect(usersService.updateMine).toHaveBeenCalledWith(
      expect.objectContaining({ id: safeDonor.id }),
      { displayName: "Updated Name" },
    );
  });

  it("restricts facility administration and worker verification to database-authenticated admins", async () => {
    const createResult = {
      id: "00000000-0000-4000-8000-000000000055",
      status: "PENDING",
    };
    facilitiesService.create.mockResolvedValue(createResult);
    const body = { name: "SisterMesh Central Clinic" };

    await request(app.getHttpServer())
      .post("/api/admin/facilities")
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .send(body)
      .expect(403);
    await request(app.getHttpServer())
      .post("/api/admin/facilities")
      .set("Authorization", await bearer(UserRole.ADMIN))
      .send(body)
      .expect(201, createResult);

    expect(facilitiesService.create).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.ADMIN }),
      body,
    );
  });

  it("does not trust client role headers over the signed-in database account", async () => {
    await request(app.getHttpServer())
      .patch(
        "/api/admin/health-workers/00000000-0000-4000-8000-000000000053/verification",
      )
      .set("Authorization", await bearer(UserRole.INDIVIDUAL))
      .set("x-test-role", UserRole.ADMIN)
      .send({ verificationStatus: "VERIFIED" })
      .expect(403);
    expect(healthWorkersService.setVerification).not.toHaveBeenCalled();
  });
});
