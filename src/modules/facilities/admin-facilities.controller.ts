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
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { FacilityStatus, UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateFacilityDto } from './dto/create-facility.dto';
import { UpdateFacilityDto } from './dto/update-facility.dto';
import { UpdateFacilityStatusDto } from './dto/update-facility-status.dto';
import { FacilitiesService } from './facilities.service';

@ApiTags('Facility administration')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/facilities')
export class AdminFacilitiesController {
  constructor(private readonly service: FacilitiesService) {}

  @Post()
  @ApiOperation({ summary: 'Register a facility in pending status.' })
  create(@CurrentUser() admin: AuthenticatedUser, @Body() dto: CreateFacilityDto) {
    return this.service.create(admin, dto);
  }

  @Get()
  @ApiOperation({ summary: 'List all facilities for administrators.' })
  list() {
    return this.service.listForAdmin();
  }

  @Get(':facilityId')
  @ApiOperation({ summary: 'Retrieve full facility details for administrators.' })
  get(@Param('facilityId', ParseUUIDPipe) facilityId: string) {
    return this.service.getForAdmin(facilityId);
  }

  @Get(':facilityId/health-workers')
  @ApiOperation({ summary: 'List health workers associated with a facility.' })
  listWorkers(@Param('facilityId', ParseUUIDPipe) facilityId: string) {
    return this.service.listHealthWorkers(facilityId);
  }

  @Patch(':facilityId')
  @ApiOperation({ summary: 'Update facility details without changing approval status.' })
  update(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('facilityId', ParseUUIDPipe) facilityId: string,
    @Body() dto: UpdateFacilityDto,
  ) {
    return this.service.update(admin, facilityId, dto);
  }

  @Patch(':facilityId/status')
  @ApiOperation({ summary: 'Approve or deactivate a facility.' })
  updateStatus(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('facilityId', ParseUUIDPipe) facilityId: string,
    @Body() dto: UpdateFacilityStatusDto,
  ) {
    return this.service.updateStatus(admin, facilityId, dto.status as FacilityStatus);
  }
}
