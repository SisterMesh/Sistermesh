import {
  Body,
  Controller,
  Param,
  ParseUUIDPipe,
  Patch,
  UseGuards,
} from '@nestjs/common';
import { UserRole } from '@prisma/client';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiParam,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { Roles } from '../../common/auth/roles.decorator';
import { RolesGuard } from '../../common/auth/roles.guard';
import { AssignFacilityDto } from './dto/assign-facility.dto';
import { SetVerificationStatusDto } from './dto/set-verification-status.dto';
import { HealthWorkersService } from './health-workers.service';

@ApiTags('admin health workers')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/health-workers')
export class AdminHealthWorkersController {
  constructor(private readonly healthWorkersService: HealthWorkersService) {}

  @Patch(':userId/verification')
  @ApiOperation({
    summary: 'Verify or reject a health-worker profile',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'userId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Verification status updated' })
  @ApiResponse({ status: 403, description: 'Administrator role required' })
  setVerificationStatus(
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: SetVerificationStatusDto,
  ) {
    return this.healthWorkersService.setVerificationStatus(userId, dto.status);
  }

  @Patch(':userId/facility')
  @ApiOperation({
    summary: 'Associate a health worker with an active facility',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'userId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Facility association updated' })
  @ApiResponse({ status: 403, description: 'Administrator role or active facility required' })
  assignFacility(
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: AssignFacilityDto,
  ) {
    return this.healthWorkersService.assignFacility(userId, dto);
  }
}
