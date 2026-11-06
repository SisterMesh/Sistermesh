import {
  Controller,
  Get,
  Param,
  ParseUUIDPipe,
  UseGuards,
} from '@nestjs/common';
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
import { FacilitiesService } from './facilities.service';

@ApiTags('facilities')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('facilities')
export class FacilitiesController {
  constructor(private readonly facilitiesService: FacilitiesService) {}

  @Get()
  @ApiOperation({
    summary: 'List active facilities; administrators can also see pending facilities',
  })
  @ApiResponse({ status: 200, description: 'Facility summaries' })
  list(@CurrentUser() user: AuthenticatedUser) {
    return this.facilitiesService.listForUser(user);
  }

  @Get(':facilityId')
  @ApiOperation({ summary: 'Get a facility the user is authorised to access' })
  @ApiParam({ name: 'facilityId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Facility details' })
  @ApiResponse({ status: 403, description: 'Health worker is not assigned to this facility' })
  get(
    @Param('facilityId', ParseUUIDPipe) facilityId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.facilitiesService.getForUser(facilityId, user);
  }
}
