import { Controller, Get, Param, ParseUUIDPipe, Patch, Post, Body, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { EmergencyRequestStatus, UserRole } from '@prisma/client';
import { CurrentUser, AuthenticatedUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateEmergencyRequestDto } from './dto/create-emergency-request.dto';
import { UpdateRequestStatusDto } from './dto/update-request-status.dto';
import { EmergencyRequestsService } from './emergency-requests.service';

@ApiTags('Emergency requests')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('emergency-requests')
export class EmergencyRequestsController {
  constructor(private readonly service: EmergencyRequestsService) {}

  @Post()
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Create a facility emergency request; replay safely with clientRequestId.' })
  @ApiResponse({ status: 201, description: 'Request created or the matching prior request returned.' })
  create(@CurrentUser() user: AuthenticatedUser, @Body() dto: CreateEmergencyRequestDto) {
    return this.service.create(user, dto);
  }

  @Get('eligible')
  @Roles(UserRole.DONOR)
  @ApiOperation({ summary: 'List current requests compatible with the signed-in donor.' })
  eligible(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listEligible(user);
  }

  @Get()
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'List requests for the signed-in health worker facility.' })
  list(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listForFacility(user);
  }

  @Get(':requestId/history')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Read the auditable activity history for a facility request.' })
  history(@CurrentUser() user: AuthenticatedUser, @Param('requestId', ParseUUIDPipe) requestId: string) {
    return this.service.history(user, requestId);
  }

  @Post(':requestId/cancel')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Cancel an active facility request.' })
  cancel(@CurrentUser() user: AuthenticatedUser, @Param('requestId', ParseUUIDPipe) requestId: string) {
    return this.service.cancel(user, requestId);
  }

  @Patch(':requestId/status')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Move a facility request through an allowed status transition.' })
  updateStatus(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: UpdateRequestStatusDto,
  ) {
    return this.service.updateStatus(user, requestId, dto.status as EmergencyRequestStatus);
  }
}
