import { PrismaService } from '../src/modules/prisma/prisma.service';

describe('Neon database connectivity', () => {
  let prisma: PrismaService;

  beforeAll(() => {
    if (!process.env.DATABASE_URL) {
      throw new Error('DATABASE_URL must be configured to run database tests.');
    }
    prisma = new PrismaService();
  });

  afterAll(async () => {
    await prisma?.$disconnect();
  });

  it('executes a read-only query against the configured database', async () => {
    const rows = await prisma.$queryRaw<Array<{ result: number }>>`SELECT 1 AS result`;
    expect(Number(rows[0]?.result)).toBe(1);
  });
});
