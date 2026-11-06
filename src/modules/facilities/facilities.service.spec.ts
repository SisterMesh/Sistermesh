import { FacilityStatus } from '@prisma/client';
import { AuthenticatedUser } from '../../common/authenticated-user';
import { PrismaService } from '../prisma/prisma.service';
import { FacilitiesService } from './facilities.service';

describe('FacilitiesService', () => {
  const admin: AuthenticatedUser = {
    id: '00000000-0000-4000-8000-000000000081',
    role: 'ADMIN',
    status: 'ACTIVE',
  };

  it('creates a facility pending approval and records its administrative audit event', async () => {
    const facility = {
      id: '00000000-0000-4000-8000-000000000082',
      name: 'Central Clinic',
      status: FacilityStatus.PENDING,
    };
    const tx = {
      facility: { create: jest.fn().mockResolvedValue(facility) },
      auditLog: { create: jest.fn().mockResolvedValue({}) },
    };
    const prismaMock = {
      $transaction: jest.fn((callback: (transaction: typeof tx) => unknown) => callback(tx)),
    };
    const service = new FacilitiesService(prismaMock as unknown as PrismaService);

    await expect(service.create(admin, { name: 'Central Clinic' })).resolves.toBe(facility);
    expect(tx.facility.create.mock.calls[0][0].data.status).toBe(FacilityStatus.PENDING);
    expect(tx.auditLog.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          actorUserId: admin.id,
          action: 'FACILITY_CREATED',
          entityId: facility.id,
        }),
      }),
    );
  });

  it('lists only active facilities on the public directory', async () => {
    const prismaMock = { facility: { findMany: jest.fn().mockResolvedValue([]) } };
    const service = new FacilitiesService(prismaMock as unknown as PrismaService);

    await service.list();
    expect(prismaMock.facility.findMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: { status: FacilityStatus.ACTIVE } }),
    );
  });
});
