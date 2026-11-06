import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { RolesGuard } from '../../common/auth/roles.guard';
import { DonorsController } from './donors.controller';
import { DonorsService } from './donors.service';

describe('DonorsController validation', () => {
  let app: INestApplication;
  const donorsService = {
    getOwnProfile: jest.fn(),
    updateOwnProfile: jest.fn(),
  };

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      controllers: [DonorsController],
      providers: [
        { provide: DonorsService, useValue: donorsService },
        RolesGuard,
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({
        canActivate: (context: {
          switchToHttp: () => { getRequest: () => { user?: unknown } };
        }) => {
          context.switchToHttp().getRequest().user = {
            id: 'donor-id',
            role: 'DONOR',
            displayName: 'Donor',
            phoneNumber: '+2348012345678',
          };
          return true;
        },
      })
      .compile();
    app = moduleRef.createNestApplication();
    app.setGlobalPrefix('api');
    app.useGlobalPipes(
      new ValidationPipe({
        whitelist: true,
        forbidNonWhitelisted: true,
        transform: true,
      }),
    );
    await app.init();
  });

  beforeEach(() => {
    jest.clearAllMocks();
    donorsService.updateOwnProfile.mockResolvedValue({ userId: 'donor-id' });
  });

  afterAll(async () => {
    await app?.close();
  });

  it('rejects invalid blood groups and fields that could assign another profile', async () => {
    await request(app.getHttpServer())
      .patch('/api/donors/me')
      .send({ bloodGroup: 'INVALID', userId: 'other-user' })
      .expect(400);
    expect(donorsService.updateOwnProfile).not.toHaveBeenCalled();
  });

  it('accepts a valid profile update for the authenticated donor', async () => {
    await request(app.getHttpServer())
      .patch('/api/donors/me')
      .send({ bloodGroup: 'O_POSITIVE', locality: 'Rumuola', isAvailable: true })
      .expect(200);
    expect(donorsService.updateOwnProfile).toHaveBeenCalledWith(
      'donor-id',
      {
        bloodGroup: 'O_POSITIVE',
        locality: 'Rumuola',
        isAvailable: true,
      },
    );
  });
});
