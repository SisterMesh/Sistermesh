import { AccountStatus, PrismaClient, UserRole } from '@prisma/client';
import { stdin, stdout } from 'node:process';
import { createInterface } from 'node:readline/promises';

async function main(): Promise<void> {
  if (!process.env.DATABASE_URL) {
    throw new Error('DATABASE_URL must be configured in Replit Secrets.');
  }

  const prisma = new PrismaClient();
  const terminal = createInterface({ input: stdin, output: stdout });
  try {
    const phoneNumber = (await terminal.question('Existing account phone (E.164): ')).trim();
    const user = await prisma.user.findUnique({
      where: { phoneNumber },
      select: {
        id: true,
        displayName: true,
        phoneNumber: true,
        role: true,
        status: true,
        passwordHash: true,
      },
    });

    if (!user || !user.passwordHash || user.status !== AccountStatus.ACTIVE) {
      throw new Error('An active account with a password must exist before promotion.');
    }
    if (user.role === UserRole.ADMIN) {
      throw new Error('This account is already an administrator.');
    }

    const confirmation = await terminal.question(
      `Type PROMOTE to grant ADMIN to ${user.displayName} (${user.phoneNumber}): `,
    );
    if (confirmation !== 'PROMOTE') {
      stdout.write('No role changes were made.\n');
      return;
    }

    await prisma.$transaction(async (tx) => {
      await tx.user.update({
        where: { id: user.id },
        data: { role: UserRole.ADMIN },
      });
      await tx.auditLog.create({
        data: {
          action: 'ADMIN_BOOTSTRAP_PROMOTION',
          entityType: 'User',
          entityId: user.id,
          metadata: { method: 'controlled_cli' },
        },
      });
    });
    stdout.write(`Administrator role granted to ${user.displayName}.\n`);
  } finally {
    terminal.close();
    await prisma.$disconnect();
  }
}

void main().catch((error: unknown) => {
  const message =
    error instanceof Error ? error.message : 'Administrator promotion failed.';
  console.error(message);
  process.exitCode = 1;
});
