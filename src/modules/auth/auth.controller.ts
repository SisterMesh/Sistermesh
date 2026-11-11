import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Patch,
  Post,
  Req,
  Res,
  UnauthorizedException,
} from '@nestjs/common';
import { ApiBearerAuth, ApiCookieAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { PublicRoute } from '../../common/public-route.decorator';
import { AuthService, AuthSession } from './auth.service';
import { LoginDto } from './dto/login.dto';
import { RegisterDonorDto } from './dto/register-donor.dto';
import { RegisterDto } from './dto/register.dto';
import { ChangePasswordDto } from './dto/change-password.dto';
import {
  clearRefreshCookie,
  getRefreshCookie,
  setRefreshCookie,
} from './refresh-cookie';

interface HeaderResponse {
  setHeader(name: string, value: string): void;
}

interface CookieRequest {
  headers: { cookie?: string };
}

@ApiTags('Authentication')
@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('register')
  @PublicRoute()
  @ApiOperation({ summary: 'Register an individual account. Public registration cannot create administrators.' })
  @ApiResponse({ status: 201, description: 'Account created with its role-specific profile.' })
  async register(@Body() dto: RegisterDto, @Res({ passthrough: true }) response: HeaderResponse) {
    return this.sessionResponse(await this.authService.register(dto), response);
  }

  @Post('register/donor')
  @PublicRoute()
  @ApiOperation({ summary: 'Legacy route: register an individual account with a donor profile.' })
  @ApiResponse({ status: 201, description: 'Account created; response contains an access token and safe user profile.' })
  @ApiResponse({ status: 409, description: 'Phone number is already registered.' })
  async registerDonor(@Body() dto: RegisterDonorDto, @Res({ passthrough: true }) response: HeaderResponse) {
    return this.sessionResponse(await this.authService.registerDonor(dto), response);
  }

  @Post('login')
  @PublicRoute()
  @ApiOperation({ summary: 'Authenticate with phone number and password.' })
  @ApiResponse({ status: 401, description: 'Phone number or password is incorrect.' })
  async login(@Body() dto: LoginDto, @Res({ passthrough: true }) response: HeaderResponse) {
    return this.sessionResponse(await this.authService.login(dto), response);
  }

  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  @PublicRoute()
  @ApiCookieAuth('refreshCookie')
  @ApiOperation({ summary: 'Rotate the HttpOnly refresh session and return a new access token.' })
  @ApiResponse({ status: 401, description: 'Refresh session is missing, expired, revoked, or invalid.' })
  async refresh(@Req() request: CookieRequest, @Res({ passthrough: true }) response: HeaderResponse) {
    const token = getRefreshCookie(request);
    if (!token) throw new UnauthorizedException('Refresh session is invalid or expired.');
    return this.sessionResponse(await this.authService.rotateRefreshToken(token), response);
  }

  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  @PublicRoute()
  @ApiCookieAuth('refreshCookie')
  @ApiOperation({ summary: 'Revoke the current refresh session and clear its cookie.' })
  async logout(@Req() request: CookieRequest, @Res({ passthrough: true }) response: HeaderResponse) {
    await this.authService.logout(getRefreshCookie(request));
    clearRefreshCookie(response);
  }

  @Get('me')
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Retrieve the authenticated user and their own profile.' })
  @ApiResponse({ status: 401, description: 'A valid access token is required.' })
  profile(@CurrentUser() user: AuthenticatedUser) {
    return this.authService.getProfile(user.id);
  }

  @Patch('password')
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Change the authenticated account password and revoke refresh sessions.' })
  @ApiResponse({ status: 204, description: 'Password changed and refresh sessions revoked.' })
  async changePassword(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: ChangePasswordDto,
  ): Promise<void> {
    await this.authService.changePassword(user.id, dto);
  }

  private sessionResponse(session: AuthSession, response: HeaderResponse) {
    setRefreshCookie(response, session.refreshToken);
    return { accessToken: session.accessToken, user: session.user };
  }
}
