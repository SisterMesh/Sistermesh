import { createHmac } from 'node:crypto';

export function deriveJwtKey(purpose: 'access' | 'refresh'): Buffer {
  const sessionSecret = process.env.SESSION_SECRET;
  if (!sessionSecret || Buffer.byteLength(sessionSecret) < 32) {
    throw new Error('SESSION_SECRET must be configured with at least 32 bytes.');
  }

  return createHmac('sha256', sessionSecret)
    .update(`sistermesh:jwt:${purpose}:v1`)
    .digest();
}
