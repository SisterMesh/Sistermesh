"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const client_1 = require("@prisma/client");
const node_process_1 = require("node:process");
const promises_1 = require("node:readline/promises");
async function main() {
    if (!process.env.DATABASE_URL) {
        throw new Error('DATABASE_URL must be configured in Replit Secrets.');
    }
    const prisma = new client_1.PrismaClient();
    const terminal = (0, promises_1.createInterface)({ input: node_process_1.stdin, output: node_process_1.stdout });
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
        if (!user || !user.passwordHash || user.status !== client_1.AccountStatus.ACTIVE) {
            throw new Error('An active account with a password must exist before promotion.');
        }
        if (user.role === client_1.UserRole.ADMIN) {
            throw new Error('This account is already an administrator.');
        }
        const confirmation = await terminal.question(`Type PROMOTE to grant ADMIN to ${user.displayName} (${user.phoneNumber}): `);
        if (confirmation !== 'PROMOTE') {
            node_process_1.stdout.write('No role changes were made.\n');
            return;
        }
        await prisma.$transaction(async (tx) => {
            await tx.user.update({
                where: { id: user.id },
                data: { role: client_1.UserRole.ADMIN },
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
        node_process_1.stdout.write(`Administrator role granted to ${user.displayName}.\n`);
    }
    finally {
        terminal.close();
        await prisma.$disconnect();
    }
}
void main().catch((error) => {
    const message = error instanceof Error ? error.message : 'Administrator promotion failed.';
    console.error(message);
    process.exitCode = 1;
});
//# sourceMappingURL=promote-admin.js.map