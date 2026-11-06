import {
  Body,
  Controller,
  Get,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
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
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { CurrentUser } from '../../common/auth/current-user.decorator';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { Roles } from '../../common/auth/roles.decorator';
import { RolesGuard } from '../../common/auth/roles.guard';
import { CreateFacilityDto } from './dto/create-facility.dto';
import { SetFacilityStatusDto } from './dto/set-facility-status.dto';
import { UpdateFacilityDto } from './dto/update-facility.dto';
import { FacilitiesService } from './facilities.service';

@ApiTags('admin facilities')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/facilities')
export class AdminFacilitiesController {
  constructor(private readonly facilitiesService: FacilitiesService) {}

  @Post()
  @ApiOperation({
    summary: 'Register a facility for administrative approval',
    description: 'Requires an active administrator access token; new facilities start PENDING.',
  })
  @ApiResponse({ status: 201, description: 'Facility created with PENDING status' })
  @ApiResponse({ status: 403, description: 'Administrator role required' })
  create(
    @CurrentUser() actor: AuthenticatedUser,
    @Body() dto: CreateFacilityDto,
  ) {
    return this.facilitiesService.create(actor.id, dto);
  }

  @Patch(':facilityId')
  @ApiOperation({
    summary: 'Update facility information',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'facilityId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Facility updated' })
  update(
    @CurrentUser() actor: AuthenticatedUser,
    @Param('facilityId', ParseUUIDPipe) facilityId: string,
    @Body() dto: UpdateFacilityDto,
  ) {
    return this.facilitiesService.update(actor.id, facilityId, dto);
  }

  @Patch(':facilityId/status')
  @ApiOperation({
    summary: 'Approve, reject, activate, or disable a facility',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'facilityId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Facility status updated' })
  setStatus(
    @CurrentUser() actor: AuthenticatedUser,
    @Param('facilityId', ParseUUIDPipe) facilityId: string,
    @Body() dto: SetFacilityStatusDto,
  ) {
    return this.facilitiesService.setStatus(actor.id, facilityId, dto.status);
  }

  @Get(':facilityId/health-workers')
  @ApiOperation({
    summary: 'List health workers associated with a facility',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'facilityId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Associated health-worker summaries' })
  getHealthWorkers(@Param('facilityId', ParseUUIDPipe) facilityId: string) {
    return this.facilitiesService.getHealthWorkers(facilityId);
  }
}
