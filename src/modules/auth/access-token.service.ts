import { Injectable, InternalServerErrorException, OnModuleInit } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { createHmac } from 'node:crypto';

export const ACCESS_TOKEN_ISSUER = 'sistermesh-api';
export const ACCESS_TOKEN_AUDIENCE = 'sistermesh-clients';

export interface AccessTokenClaims {
  sub: string;
  iat: number;
  exp: number;
}

@Injectable()
export class AccessTokenService implements OnModuleInit {
  constructor(private readonly jwt: JwtService) {}

  onModuleInit(): void {
    this.signingKey();
  }

  async issue(userId: string): Promise<string> {
    return this.jwt.signAsync(
      {},
      {
        secret: this.signingKey(),
        subject: userId,
        issuer: ACCESS_TOKEN_ISSUER,
        audience: ACCESS_TOKEN_AUDIENCE,
        expiresIn: '15m',
        algorithm: 'HS256',
      },
    );
  }

  async verify(token: string): Promise<AccessTokenClaims> {
    const claims = await this.jwt.verifyAsync<AccessTokenClaims>(token, {
      secret: this.signingKey(),
      issuer: ACCESS_TOKEN_ISSUER,
      audience: ACCESS_TOKEN_AUDIENCE,
      algorithms: ['HS256'],
    });
    if (
      typeof claims !== 'object' ||
      typeof claims.sub !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claims.sub)
    ) {
      throw new Error('Invalid access token subject.');
    }
    return claims;
  }

  private signingKey(): Buffer {
    const secret = process.env.SESSION_SECRET;
    if (!secret || Buffer.byteLength(secret) < 32) {
      throw new InternalServerErrorException(
        'SESSION_SECRET must contain at least 32 bytes for access-token signing.',
      );
    }
    return createHmac('sha256', secret)
      .update('sistermesh:access-token:v1')
      .digest();
  }
}
