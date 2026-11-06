import { Prisma } from '@prisma/client';

type RequestActivityData = Omit<Prisma.RequestActivityUncheckedCreateInput, 'syncCursor'>;

export async function createSyncActivity(tx: Prisma.TransactionClient, data: RequestActivityData) {
  const cursorState = await tx.syncCursorState.update({
    where: { id: 1 },
    data: { value: { increment: 1 } },
    select: { value: true },
  });
  return tx.requestActivity.create({
    data: { ...data, syncCursor: cursorState.value },
  });
}
