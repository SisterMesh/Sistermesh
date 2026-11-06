import {
  Body,
  Controller,
  Get,
  Header,
  HttpCode,
  HttpStatus,
  Patch,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiBody,
  ApiCookieAuth,
  ApiOperation,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { Request, Response } from 'express';
import { CurrentUser } from '../../common/auth/current-user.decorator';
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { AuthService, AuthSession } from './auth.service';
import { ChangePasswordDto } from './dto/change-password.dto';
import { LoginDto } from './dto/login.dto';
import { RegisterDto } from './dto/register.dto';

const REFRESH_COOKIE = 'sistermesh_refresh';
const REFRESH_COOKIE_PATH = '/api/auth';
const REFRESH_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

@ApiTags('authentication')
@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('register')
  @Header('Cache-Control', 'no-store')
  @ApiOperation({ summary: 'Register a donor or health-worker account' })
  @ApiBody({ type: RegisterDto })
  @ApiResponse({ status: 201, description: 'Account created and signed in' })
  @ApiResponse({ status: 400, description: 'Invalid registration details' })
  @ApiResponse({ status: 409, description: 'Phone number is already registered' })
  async register(
    @Body() dto: RegisterDto,
    @Res({ passthrough: true }) response: Response,
  ) {
    const session = await this.authService.register(dto);
    this.setRefreshCookie(response, session.refreshToken);
    return this.responseBody(session);
  }

  @Post('login')
  @Header('Cache-Control', 'no-store')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Sign in with phone number and password' })
  @ApiBody({ type: LoginDto })
  @ApiResponse({ status: 200, description: 'Signed in successfully' })
  @ApiResponse({ status: 401, description: 'Invalid credentials' })
  async login(
    @Body() dto: LoginDto,
    @Res({ passthrough: true }) response: Response,
  ) {
    const session = await this.authService.login(dto);
    this.setRefreshCookie(response, session.refreshToken);
    return this.responseBody(session);
  }

  @Post('refresh')
  @Header('Cache-Control', 'no-store')
  @HttpCode(HttpStatus.OK)
  @ApiCookieAuth('sistermesh_refresh')
  @ApiOperation({
    summary: 'Rotate the refresh cookie and issue a new access token',
  })
  @ApiResponse({ status: 200, description: 'Tokens rotated successfully' })
  @ApiResponse({ status: 401, description: 'Refresh cookie is invalid or expired' })
  async refresh(
    @Req() request: Request,
    @Res({ passthrough: true }) response: Response,
  ) {
    const refreshToken = this.getRefreshCookie(request);
    if (!refreshToken) {
      return this.authService.rotateRefreshToken('');
    }
    const session = await this.authService.rotateRefreshToken(refreshToken);
    this.setRefreshCookie(response, session.refreshToken);
    return this.responseBody(session);
  }

  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiCookieAuth('sistermesh_refresh')
  @ApiOperation({ summary: 'Revoke the refresh cookie and sign out' })
  @ApiResponse({ status: 204, description: 'Signed out' })
  async logout(
    @Req() request: Request,
    @Res({ passthrough: true }) response: Response,
  ): Promise<void> {
    await this.authService.logout(this.getRefreshCookie(request));
    response.clearCookie(REFRESH_COOKIE, this.cookieOptions());
  }

  @Get('me')
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Return the authenticated user profile' })
  @ApiResponse({ status: 200, description: 'Current account details' })
  @ApiResponse({ status: 401, description: 'Access token is invalid or expired' })
  me(@CurrentUser() user: AuthenticatedUser) {
    return this.authService.getCurrentUser(user.id);
  }

  @Patch('password')
  @HttpCode(HttpStatus.NO_CONTENT)
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Change the authenticated user password' })
  @ApiBody({ type: ChangePasswordDto })
  @ApiResponse({ status: 204, description: 'Password changed; sessions revoked' })
  @ApiResponse({ status: 401, description: 'Current password is incorrect' })
  async changePassword(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: ChangePasswordDto,
  ): Promise<void> {
    await this.authService.changePassword(user.id, dto);
  }

  private responseBody({ refreshToken: _refreshToken, ...body }: AuthSession) {
    return body;
  }

  private setRefreshCookie(response: Response, token: string): void {
    response.cookie(REFRESH_COOKIE, token, {
      ...this.cookieOptions(),
      maxAge: REFRESH_MAX_AGE_MS,
    });
  }

  private cookieOptions() {
    return {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict' as const,
      path: REFRESH_COOKIE_PATH,
    };
  }

  private getRefreshCookie(request: Request): string | undefined {
    const cookieHeader = request.headers.cookie;
    if (!cookieHeader) return undefined;
    for (const part of cookieHeader.split(';')) {
      const separator = part.indexOf('=');
      if (separator < 0) continue;
      const name = part.slice(0, separator).trim();
      if (name === REFRESH_COOKIE) {
        try {
          return decodeURIComponent(part.slice(separator + 1).trim());
        } catch {
          return undefined;
        }
      }
    }
    return undefined;
  }
}
