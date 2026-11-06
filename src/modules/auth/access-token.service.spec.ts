import { JwtService } from '@nestjs/jwt';
import { createHmac } from 'node:crypto';
import { AccessTokenService } from './access-token.service';
import { ACCESS_TOKEN_AUDIENCE, ACCESS_TOKEN_ISSUER } from './access-token.service';

describe('AccessTokenService', () => {
  const originalSessionSecret = process.env.SESSION_SECRET;

  beforeAll(() => {
    process.env.SESSION_SECRET = 'test-only-session-secret-with-more-than-32-bytes';
  });

  afterAll(() => {
    if (originalSessionSecret === undefined) delete process.env.SESSION_SECRET;
    else process.env.SESSION_SECRET = originalSessionSecret;
  });

  it('issues and verifies short-lived subject-only access tokens', async () => {
    const service = new AccessTokenService(new JwtService({}));
    const userId = '00000000-0000-4000-8000-000000000041';
    const token = await service.issue(userId);
    const claims = await service.verify(token);

    expect(claims.sub).toBe(userId);
    expect(claims.exp).toBeGreaterThan(claims.iat);
  });

  it('rejects expired tokens', async () => {
    const secret = createHmac('sha256', process.env.SESSION_SECRET!)
      .update('sistermesh:access-token:v1')
      .digest();
    const jwt = new JwtService({});
    const expiredToken = await jwt.signAsync(
      {},
      {
        secret,
        subject: '00000000-0000-4000-8000-000000000042',
        issuer: ACCESS_TOKEN_ISSUER,
        audience: ACCESS_TOKEN_AUDIENCE,
        expiresIn: '-1s',
        algorithm: 'HS256',
      },
    );
    const service = new AccessTokenService(new JwtService({}));

    await expect(service.verify(expiredToken)).rejects.toBeDefined();
  });
});
