import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { RolesGuard } from '../../common/roles.guard';
import { UsersController } from './users.controller';
import { UsersService } from './users.service';

describe('UsersController account ownership', () => {
  let app: INestApplication;
  const usersService = {
    updateMine: jest.fn().mockResolvedValue({
      id: 'current-user',
      displayName: 'Updated Name',
      phoneNumber: '+2348012345678',
      role: 'DONOR',
    }),
  };

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      controllers: [UsersController],
      providers: [{ provide: UsersService, useValue: usersService }],
    })
      .overrideGuard(RolesGuard)
      .useValue({
        canActivate: (context: {
          switchToHttp: () => { getRequest: () => { user?: unknown } };
        }) => {
          context.switchToHttp().getRequest().user = {
            id: 'current-user',
            role: 'DONOR',
            status: 'ACTIVE',
            displayName: 'Current User',
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

  afterAll(async () => {
    await app?.close();
  });

  it('updates only the authenticated user account', async () => {
    await request(app.getHttpServer())
      .patch('/api/users/me')
      .send({ displayName: 'Updated Name' })
      .expect(200)
      .expect(({ body }) => {
        expect(body.id).toBe('current-user');
      });
    expect(usersService.updateMine).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'current-user' }),
      { displayName: 'Updated Name' },
    );
  });

  it('does not expose an endpoint that accepts another user ID for profile updates', async () => {
    await request(app.getHttpServer())
      .patch('/api/users/another-user-id')
      .send({ displayName: 'Unauthorized Change' })
      .expect(404);
    expect(usersService.updateMine).toHaveBeenCalledTimes(1);
  });
});
