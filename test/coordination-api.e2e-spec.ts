import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { UserRole } from '@prisma/client';
import request from 'supertest';
import { AuthenticatedUser } from '../src/common/authenticated-user';
import { RolesGuard } from '../src/common/roles.guard';
import { AuthModule } from '../src/modules/auth/auth.module';
import { AccessTokenService } from '../src/modules/auth/access-token.service';
import { DonationConfirmationsController } from '../src/modules/donation-confirmations/donation-confirmations.controller';
import { DonationConfirmationsService } from '../src/modules/donation-confirmations/donation-confirmations.service';
import { DonorResponsesController } from '../src/modules/donor-responses/donor-responses.controller';
import { DonorResponsesService } from '../src/modules/donor-responses/donor-responses.service';
import { EmergencyRequestsController } from '../src/modules/emergency-requests/emergency-requests.controller';
import { EmergencyRequestsService } from '../src/modules/emergency-requests/emergency-requests.service';
import { PrismaModule } from '../src/modules/prisma/prisma.module';
import { PrismaService } from '../src/modules/prisma/prisma.service';

describe('coordination API with verified JWT authentication', () => {
  let app: INestApplication;
  let accessTokens: AccessTokenService;
  const originalSessionSecret = process.env.SESSION_SECRET;
  const actors = new Map<string, AuthenticatedUser>();
  const prismaMock = {
    user: {
      findUnique: jest.fn(),
    },
    healthWorkerProfile: {
      findUnique: jest.fn().mockResolvedValue({ verificationStatus: 'VERIFIED' }),
    },
  };
  const emergencyRequests = {
    create: jest.fn(),
    listEligible: jest.fn(),
    listForFacility: jest.fn(),
    history: jest.fn(),
    cancel: jest.fn(),
    updateStatus: jest.fn(),
  };
  const donorResponses = { respond: jest.fn(), listMine: jest.fn(), withdraw: jest.fn(), listForRequest: jest.fn() };
  const donationConfirmations = { confirm: jest.fn() };

  beforeAll(async () => {
    process.env.SESSION_SECRET = 'test-only-session-secret-with-more-than-32-bytes';
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id: string } }) => actors.get(args.where.id) ?? null,
    );
    const module = await Test.createTestingModule({
      imports: [PrismaModule, AuthModule],
      controllers: [
        EmergencyRequestsController,
        DonorResponsesController,
        DonationConfirmationsController,
      ],
      providers: [
        RolesGuard,
        { provide: EmergencyRequestsService, useValue: emergencyRequests },
        { provide: DonorResponsesService, useValue: donorResponses },
        { provide: DonationConfirmationsService, useValue: donationConfirmations },
      ],
    })
      .overrideProvider(PrismaService)
      .useValue(prismaMock)
      .compile();
    app = module.createNestApplication();
    app.setGlobalPrefix('api');
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true, transform: true }));
    await app.init();
    accessTokens = module.get<AccessTokenService>(AccessTokenService);
  });

  beforeEach(() => {
    jest.clearAllMocks();
    actors.clear();
    prismaMock.user.findUnique.mockImplementation(
      async (args: { where: { id: string } }) => actors.get(args.where.id) ?? null,
    );
    prismaMock.healthWorkerProfile.findUnique.mockResolvedValue({ verificationStatus: 'VERIFIED' });
  });

  afterAll(async () => {
    await app.close();
    if (originalSessionSecret === undefined) delete process.env.SESSION_SECRET;
    else process.env.SESSION_SECRET = originalSessionSecret;
  });

  async function bearer(role: UserRole, status: AuthenticatedUser['status'] = 'ACTIVE') {
    const id =
      role === UserRole.DONOR
        ? '00000000-0000-4000-8000-000000000002'
        : role === UserRole.HEALTH_WORKER
          ? '00000000-0000-4000-8000-000000000001'
          : '00000000-0000-4000-8000-000000000003';
    actors.set(id, { id, role, status });
    return `Bearer ${await accessTokens.issue(id)}`;
  }

  it('rejects missing or invalid tokens, wrong roles, and invalid request data', async () => {
    const body = {
      clientRequestId: '00000000-0000-4000-8000-000000000011',
      requestedBloodGroup: 'O_NEGATIVE',
      urgency: 'CRITICAL',
      units: 2,
    };
    await request(app.getHttpServer()).post('/api/emergency-requests').send(body).expect(401);
    await request(app.getHttpServer())
      .post('/api/emergency-requests')
      .set('Authorization', 'Bearer not-a-jwt')
      .send(body)
      .expect(401);
    await request(app.getHttpServer())
      .post('/api/emergency-requests')
      .set('Authorization', await bearer(UserRole.DONOR))
      .send(body)
      .expect(403);
    await request(app.getHttpServer())
      .post('/api/emergency-requests')
      .set('Authorization', await bearer(UserRole.HEALTH_WORKER, 'SUSPENDED'))
      .send(body)
      .expect(401);
    await request(app.getHttpServer())
      .post('/api/emergency-requests')
      .set('Authorization', await bearer(UserRole.HEALTH_WORKER))
      .send({ ...body, units: 0 })
      .expect(400);
  });

  it('accepts a signed-in worker request using the database role, not client headers', async () => {
    const created = { id: '00000000-0000-4000-8000-000000000010', status: 'OPEN' };
    const body = {
      clientRequestId: '00000000-0000-4000-8000-000000000011',
      requestedBloodGroup: 'O_NEGATIVE',
      urgency: 'CRITICAL',
      units: 2,
    };
    emergencyRequests.create.mockResolvedValue(created);

    await request(app.getHttpServer())
      .post('/api/emergency-requests')
      .set('Authorization', await bearer(UserRole.HEALTH_WORKER))
      .set('x-test-role', UserRole.ADMIN)
      .send(body)
      .expect(201, created);

    expect(emergencyRequests.create).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.HEALTH_WORKER }),
      body,
    );
  });

  it('lists compatible requests and accepts a donor response under the donor token', async () => {
    const eligible = [{ id: 'request-1', requestedBloodGroup: 'O_NEGATIVE' }];
    const response = { id: 'response-1', status: 'ACCEPTED' };
    emergencyRequests.listEligible.mockResolvedValue(eligible);
    donorResponses.respond.mockResolvedValue(response);

    await request(app.getHttpServer())
      .get('/api/emergency-requests/eligible')
      .set('Authorization', await bearer(UserRole.DONOR))
      .expect(200, eligible);
    await request(app.getHttpServer())
      .post('/api/donor-responses/00000000-0000-4000-8000-000000000010')
      .set('Authorization', await bearer(UserRole.DONOR))
      .send({
        idempotencyKey: '00000000-0000-4000-8000-000000000012',
        status: 'ACCEPTED',
      })
      .expect(201, response);

    expect(donorResponses.respond).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.DONOR }),
      '00000000-0000-4000-8000-000000000010',
      { idempotencyKey: '00000000-0000-4000-8000-000000000012', status: 'ACCEPTED' },
    );
  });

  it('confirms a donation only through a health worker bearer token', async () => {
    const confirmation = { id: 'confirmation-1' };
    donationConfirmations.confirm.mockResolvedValue(confirmation);
    const body = {
      idempotencyKey: '00000000-0000-4000-8000-000000000013',
      donorProfileId: '00000000-0000-4000-8000-000000000014',
      receiptHash: 'a'.repeat(64),
    };

    await request(app.getHttpServer())
      .post('/api/emergency-requests/00000000-0000-4000-8000-000000000010/donations/confirm')
      .set('Authorization', await bearer(UserRole.HEALTH_WORKER))
      .send(body)
      .expect(201, confirmation);

    expect(donationConfirmations.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.HEALTH_WORKER }),
      '00000000-0000-4000-8000-000000000010',
      body,
    );
  });

  it('restricts facility response lists to health workers and supports donor withdrawal retries', async () => {
    const requestId = '00000000-0000-4000-8000-000000000020';
    const rows = [{ id: 'response-1', status: 'ACCEPTED' }];
    const withdrawn = { id: 'response-1', status: 'WITHDRAWN' };
    donorResponses.listForRequest.mockResolvedValue(rows);
    donorResponses.withdraw.mockResolvedValue(withdrawn);

    await request(app.getHttpServer())
      .get(`/api/donor-responses/requests/${requestId}`)
      .set('Authorization', await bearer(UserRole.DONOR))
      .expect(403);
    await request(app.getHttpServer())
      .get(`/api/donor-responses/requests/${requestId}`)
      .set('Authorization', await bearer(UserRole.HEALTH_WORKER))
      .expect(200, rows);
    await request(app.getHttpServer())
      .post(`/api/donor-responses/${requestId}/withdraw`)
      .set('Authorization', await bearer(UserRole.DONOR))
      .send({ idempotencyKey: '00000000-0000-4000-8000-000000000021' })
      .expect(201, withdrawn);

    expect(donorResponses.listForRequest).toHaveBeenCalled();
    expect(donorResponses.withdraw).toHaveBeenCalledWith(
      expect.objectContaining({ role: UserRole.DONOR }),
      requestId,
      { idempotencyKey: '00000000-0000-4000-8000-000000000021' },
    );
  });
});
