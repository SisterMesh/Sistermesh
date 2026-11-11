import { Controller, Get, Param, ParseUUIDPipe, Patch, Post, Body, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { CurrentUser, AuthenticatedUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateEmergencyRequestDto } from './dto/create-emergency-request.dto';
import { UpdateRequestSubmissionDto } from './dto/update-request-submission.dto';
import { EmergencyRequestsService } from './emergency-requests.service';

@ApiTags('Emergency requests')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('emergency-requests')
export class EmergencyRequestsController {
  constructor(private readonly service: EmergencyRequestsService) {}

  @Post()
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'Create a request with a free-form donation destination.' })
  @ApiResponse({ status: 201, description: 'Request created or the matching prior request returned.' })
  create(@CurrentUser() user: AuthenticatedUser, @Body() dto: CreateEmergencyRequestDto) {
    return this.service.create(user, dto);
  }

  @Get('eligible')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'List open requests with preliminary blood-group matching for the signed-in donor.',
    description: 'Matching is preliminary filtering only and does not establish clinical compatibility.',
  })
  eligible(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listEligible(user);
  }

  @Get('mine')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'List the signed-in individual’s own requests, including private review details.' })
  listMine(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listMine(user);
  }

  @Post(':requestId/cancel')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'Cancel the signed-in individual’s own request.' })
  @ApiResponse({ status: 409, description: 'The request is already closed or expired.' })
  cancel(@CurrentUser() user: AuthenticatedUser, @Param('requestId', ParseUUIDPipe) requestId: string) {
    return this.service.cancel(user, requestId);
  }

  @Patch(':requestId/submission')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'Update a request owned by the signed-in individual.' })
  updateSubmission(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: UpdateRequestSubmissionDto,
  ) {
    const { expectedUpdatedAt, ...updates } = dto;
    return this.service.updateSubmission(user, requestId, updates, new Date(expectedUpdatedAt));
  }

}
