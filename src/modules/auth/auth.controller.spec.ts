import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

describe('Authentication HTTP endpoints', () => {
  let app: INestApplication;
  const authService = {
    register: jest.fn(),
    login: jest.fn(),
    rotateRefreshToken: jest.fn(),
    logout: jest.fn(),
    getCurrentUser: jest.fn(),
    changePassword: jest.fn(),
  };

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      controllers: [AuthController],
      providers: [
        { provide: AuthService, useValue: authService },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
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
    authService.register.mockResolvedValue({
      accessToken: 'access-only',
      expiresIn: 900,
      refreshToken: 'private-refresh-cookie',
      user: {
        id: '29e4378f-85ab-4b4b-8ce3-6e721e45338a',
        role: 'INDIVIDUAL',
        displayName: 'Amina Donor',
        phoneNumber: '+2348012345678',
        createdAt: new Date('2026-01-01T00:00:00.000Z'),
      },
    });
    authService.login.mockResolvedValue({
      accessToken: 'access-only',
      expiresIn: 900,
      refreshToken: 'private-refresh-cookie',
      user: {
        id: '29e4378f-85ab-4b4b-8ce3-6e721e45338a',
        role: 'INDIVIDUAL',
        displayName: 'Amina Donor',
        phoneNumber: '+2348012345678',
        createdAt: new Date('2026-01-01T00:00:00.000Z'),
      },
    });
  });

  afterAll(async () => {
    await app?.close();
  });

  it('returns an access token but keeps the refresh token in an HTTP-only cookie', async () => {
    const response = await request(app.getHttpServer())
      .post('/api/auth/register')
      .send({
        displayName: 'Amina Donor',
        phoneNumber: '+2348012345678',
        password: 'Strong password 2026',
        role: 'INDIVIDUAL',
      })
      .expect(201);

    expect(response.body).toHaveProperty('accessToken', 'access-only');
    expect(response.body).not.toHaveProperty('refreshToken');
    expect(response.body).not.toHaveProperty('passwordHash');
    expect(response.headers['set-cookie'][0]).toMatch(/HttpOnly/i);
  });

  it('rejects public registration that requests the ADMIN role', async () => {
    await request(app.getHttpServer())
      .post('/api/auth/register')
      .send({
        displayName: 'Not an Admin',
        phoneNumber: '+2348012345678',
        password: 'Strong password 2026',
        role: 'ADMIN',
      })
      .expect(400);
    expect(authService.register).not.toHaveBeenCalled();
  });

  it('rejects unexpected properties rather than accepting role escalation fields', async () => {
    await request(app.getHttpServer())
      .post('/api/auth/register')
      .send({
        displayName: 'Amina Donor',
        phoneNumber: '+2348012345678',
        password: 'Strong password 2026',
        role: 'INDIVIDUAL',
        status: 'ACTIVE',
      })
      .expect(400);
    expect(authService.register).not.toHaveBeenCalled();
  });

  it('rotates refresh tokens from the cookie and omits them from JSON', async () => {
    authService.rotateRefreshToken.mockResolvedValue({
      accessToken: 'new-access',
      expiresIn: 900,
      refreshToken: 'new-private-cookie',
      user: { id: 'user-id', role: 'INDIVIDUAL' },
    });
    const response = await request(app.getHttpServer())
      .post('/api/auth/refresh')
      .set('Cookie', 'sistermesh_refresh=old-private-cookie')
      .expect(200);

    expect(authService.rotateRefreshToken).toHaveBeenCalledWith(
      'old-private-cookie',
    );
    expect(response.body).not.toHaveProperty('refreshToken');
    expect(response.headers['set-cookie'][0]).toContain('new-private-cookie');
  });

  it('revokes the refresh cookie on logout and clears it', async () => {
    await request(app.getHttpServer())
      .post('/api/auth/logout')
      .set('Cookie', 'sistermesh_refresh=private-token')
      .expect(204);

    expect(authService.logout).toHaveBeenCalledWith('private-token');
  });
});
