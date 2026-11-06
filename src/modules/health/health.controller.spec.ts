import { Test, TestingModule } from '@nestjs/testing';
import { PrismaService } from '../prisma/prisma.service';
import { HealthController } from './health.controller';
import { HealthService } from './health.service';

describe('HealthController', () => {
  let controller: HealthController;
  const prisma = {
    $queryRaw: jest.fn(),
  };

  beforeEach(async () => {
    prisma.$queryRaw.mockReset();
    const module: TestingModule = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [
        HealthService,
        { provide: PrismaService, useValue: prisma },
      ],
    }).compile();
    controller = module.get<HealthController>(HealthController);
  });

  it('reports API and database as healthy after a successful query', async () => {
    prisma.$queryRaw.mockResolvedValue([{ '?column?': 1 }]);

    await expect(controller.check()).resolves.toEqual({
      status: 'ok',
      database: 'connected',
    });
  });

  it('returns a generic unavailable status when the database query fails', async () => {
    prisma.$queryRaw.mockRejectedValue(new Error('private connection details'));

    await expect(controller.check()).rejects.toMatchObject({
      status: 503,
      response: { status: 'error', database: 'unavailable' },
    });
  });
});
