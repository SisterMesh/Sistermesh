import { Body, Controller, Delete, Get, Param, ParseUUIDPipe, Patch, Query, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { AssignWorkerFacilityDto } from './dto/assign-worker-facility.dto';
import { ListHealthWorkersQueryDto } from './dto/list-health-workers-query.dto';
import { UpdateWorkerVerificationDto } from './dto/update-worker-verification.dto';
import { HealthWorkersService } from './health-workers.service';

@ApiTags('Health worker administration')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/health-workers')
export class AdminHealthWorkersController {
  @Get()
  @ApiOperation({ summary: 'Administrator only: list workers awaiting verification or filter by status.' })
  list(@Query() query: ListHealthWorkersQueryDto) {
    return this.service.listForAdmin(query);
  }

  constructor(private readonly service: HealthWorkersService) {}

  @Patch(':userId/verification')
  @ApiOperation({ summary: 'Verify or reject a health worker’s professional profile.' })
  setVerification(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: UpdateWorkerVerificationDto,
  ) {
    return this.service.setVerification(admin, userId, dto);
  }

  @Patch(':userId/facility')
  @ApiOperation({ summary: 'Assign a verified health worker to an active facility.' })
  assignFacility(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: AssignWorkerFacilityDto,
  ) {
    return this.service.assignFacility(admin, userId, dto);
  }

  @Delete(':userId/facility')
  @ApiOperation({ summary: 'Remove a health worker’s facility assignment.' })
  removeFacility(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) userId: string,
  ) {
    return this.service.removeFacilityAssignment(admin, userId);
  }
}
