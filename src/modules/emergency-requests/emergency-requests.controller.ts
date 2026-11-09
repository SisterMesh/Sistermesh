import { Controller, Get, Param, ParseUUIDPipe, Patch, Post, Body, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { CurrentUser, AuthenticatedUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateEmergencyRequestDto } from './dto/create-emergency-request.dto';
import { ReviewEmergencyRequestDto } from './dto/review-emergency-request.dto';
import { UpdateEmergencyRequestDto } from './dto/update-emergency-request.dto';
import { UpdateRequestSubmissionDto } from './dto/update-request-submission.dto';
import { EmergencyRequestsService } from './emergency-requests.service';

@ApiTags('Emergency requests')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('emergency-requests')
export class EmergencyRequestsController {
  constructor(private readonly service: EmergencyRequestsService) {}

  @Post()
  @Roles(UserRole.INDIVIDUAL, UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Submit a request to an active facility for verification; replay safely with clientRequestId.' })
  @ApiResponse({ status: 201, description: 'Request created or the matching prior request returned.' })
  @ApiResponse({ status: 403, description: 'Health worker is unverified or not assigned to an active facility.' })
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

  @Get()
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'List requests for the signed-in health worker facility.' })
  @ApiResponse({ status: 401, description: 'A valid access token is required.' })
  @ApiResponse({ status: 403, description: 'An active, verified health worker assignment is required.' })
  list(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listForFacility(user);
  }

  @Get(':requestId/history')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Read the auditable activity history for a facility request.' })
  @ApiResponse({ status: 404, description: 'The request is not managed by the health worker’s facility.' })
  history(@CurrentUser() user: AuthenticatedUser, @Param('requestId', ParseUUIDPipe) requestId: string) {
    return this.service.history(user, requestId);
  }

  @Post(':requestId/cancel')
  @Roles(UserRole.INDIVIDUAL, UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Cancel a request owned by the individual or managed by the assigned facility.' })
  @ApiResponse({ status: 409, description: 'The request is already closed or expired.' })
  cancel(@CurrentUser() user: AuthenticatedUser, @Param('requestId', ParseUUIDPipe) requestId: string) {
    return this.service.cancel(user, requestId);
  }

  @Patch(':requestId/submission')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'Update a request that is still awaiting facility verification.' })
  updateSubmission(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: UpdateRequestSubmissionDto,
  ) {
    const { expectedUpdatedAt, ...updates } = dto;
    return this.service.updateSubmission(user, requestId, updates, new Date(expectedUpdatedAt));
  }

  @Patch(':requestId/review')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({ summary: 'Approve, reject, or request information for a request at the assigned facility.' })
  review(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: ReviewEmergencyRequestDto,
  ) {
    return this.service.review(user, requestId, dto);
  }

  @Patch(':requestId')
  @Roles(UserRole.HEALTH_WORKER)
  @ApiOperation({
    summary: 'Update request details before it closes.',
    description: 'Request lifecycle status is managed by cancellation, expiry, and confirmed donations, not client input.',
  })
  @ApiResponse({ status: 404, description: 'The request is not managed by the health worker’s facility.' })
  @ApiResponse({ status: 409, description: 'The request is closed or its required donation count is invalid.' })
  update(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: UpdateEmergencyRequestDto,
  ) {
    return this.service.update(user, requestId, dto);
  }
}
