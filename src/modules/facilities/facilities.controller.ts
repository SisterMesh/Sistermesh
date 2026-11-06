import { Controller, Get, Param, ParseUUIDPipe, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { PublicRoute } from '../../common/public-route.decorator';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { FacilitiesService } from './facilities.service';

@ApiTags('Facilities')
@Controller('facilities')
export class FacilitiesController {
  constructor(private readonly service: FacilitiesService) {}

  @Get()
  @PublicRoute()
  @ApiOperation({ summary: 'List facilities with public location details.' })
  list() {
    return this.service.list();
  }

  @Get(':facilityId')
  @ApiBearerAuth()
  @UseGuards(RolesGuard)
  @Roles(UserRole.ADMIN)
  @ApiOperation({ summary: 'Administrator only: retrieve full details of an active facility.' })
  getPublic(@Param('facilityId', ParseUUIDPipe) facilityId: string) {
    return this.service.getPublicById(facilityId);
  }
}
