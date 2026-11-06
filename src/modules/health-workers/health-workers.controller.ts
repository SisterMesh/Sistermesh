import { Body, Controller, Get, Patch, UseGuards } from '@nestjs/common';
import { UserRole } from '@prisma/client';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { CurrentUser } from '../../common/auth/current-user.decorator';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { Roles } from '../../common/auth/roles.decorator';
import { RolesGuard } from '../../common/auth/roles.guard';
import { UpdateHealthWorkerProfileDto } from './dto/update-health-worker-profile.dto';
import { HealthWorkersService } from './health-workers.service';

@ApiTags('health workers')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles(UserRole.HEALTH_WORKER)
@Controller('health-workers')
export class HealthWorkersController {
  constructor(private readonly healthWorkersService: HealthWorkersService) {}

  @Get('me')
  @ApiOperation({
    summary: 'Get the authenticated health-worker profile',
    description: 'Requires a valid access token with the HEALTH_WORKER role.',
  })
  @ApiResponse({ status: 200, description: 'Profile and verification status' })
  @ApiResponse({ status: 403, description: 'Health-worker role required' })
  getOwnProfile(@CurrentUser() user: AuthenticatedUser) {
    return this.healthWorkersService.getOwnProfile(user.id);
  }

  @Patch('me')
  @ApiOperation({
    summary: 'Update professional profile details',
    description:
      'Only the account owner can update these fields. Changed identifiers return to PENDING verification.',
  })
  @ApiResponse({ status: 200, description: 'Updated profile; changed identifiers require re-verification' })
  @ApiResponse({ status: 409, description: 'Professional identifier is already in use' })
  updateOwnProfile(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateHealthWorkerProfileDto,
  ) {
    return this.healthWorkersService.updateOwnProfile(user.id, dto);
  }
}
