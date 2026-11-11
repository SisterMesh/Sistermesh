import {
  Body,
  Controller,
  Delete,
  Get,
  Param,
  ParseUUIDPipe,
  Post,
  Query,
  UseGuards,
} from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiQuery,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { AckSyncCheckpointDto } from './dto/ack-sync-checkpoint.dto';
import { RegisterDeviceDto } from './dto/register-device.dto';
import { UploadSyncBatchDto } from './dto/upload-sync-batch.dto';
import { OfflineSyncService } from './offline-sync.service';

@ApiTags('Offline synchronization')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('sync')
export class OfflineSyncController {
  constructor(private readonly service: OfflineSyncService) {}

  @Post('devices')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Register or retrieve a device owned by the signed-in account.',
    description: 'The JWT remains the only authentication method. Revoked client device IDs cannot be reactivated.',
  })
  @ApiResponse({ status: 201, description: 'The active registered device.' })
  registerDevice(@CurrentUser() user: AuthenticatedUser, @Body() dto: RegisterDeviceDto) {
    return this.service.registerDevice(user, dto);
  }

  @Get('devices')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'List devices owned by the signed-in account, including revocation state.' })
  listDevices(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listDevices(user);
  }

  @Delete('devices/:deviceId')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Revoke an owned device and deny future uploads and downloads.',
    description: 'Revocation is idempotent and preserves the device and its audit records.',
  })
  @ApiResponse({ status: 404, description: 'The device does not belong to the signed-in account.' })
  revokeDevice(
    @CurrentUser() user: AuthenticatedUser,
    @Param('deviceId', ParseUUIDPipe) deviceId: string,
  ) {
    return this.service.revokeDevice(user, deviceId);
  }

  @Post('devices/:deviceId/batches')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Upload a batch of versioned offline coordination events.',
    description: 'Every event is processed independently. Retries must reuse clientBatchId and each eventId.',
  })
  @ApiResponse({ status: 200, description: 'A per-event result for every submitted event.' })
  @ApiResponse({ status: 403, description: 'The device is revoked, not owned by the user, or the role cannot apply the event.' })
  @ApiResponse({ status: 409, description: 'The batch ID was reused with different content.' })
  uploadBatch(
    @CurrentUser() user: AuthenticatedUser,
    @Param('deviceId', ParseUUIDPipe) deviceId: string,
    @Body() dto: UploadSyncBatchDto,
  ) {
    return this.service.uploadBatch(user, deviceId, dto);
  }

  @Get('devices/:deviceId/changes')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Read the next page of authorised server changes for this device.',
    description: 'Store all changes locally, then acknowledge exactly nextCursor before requesting the next page.',
  })
  @ApiQuery({ name: 'limit', required: false, type: Number, example: 50 })
  @ApiResponse({ status: 403, description: 'The device is revoked or the user lacks an eligible profile/facility.' })
  getChanges(
    @CurrentUser() user: AuthenticatedUser,
    @Param('deviceId', ParseUUIDPipe) deviceId: string,
    @Query('limit') limit?: string,
  ) {
    return this.service.getChanges(user, deviceId, limit === undefined ? undefined : Number(limit));
  }

  @Post('devices/:deviceId/checkpoint')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Acknowledge a fully stored download page and advance its server checkpoint.',
    description: 'Only the exact nextCursor issued for the latest page is accepted.',
  })
  @ApiResponse({ status: 409, description: 'The cursor was not issued for the current checkpoint.' })
  acknowledgeCheckpoint(
    @CurrentUser() user: AuthenticatedUser,
    @Param('deviceId', ParseUUIDPipe) deviceId: string,
    @Body() dto: AckSyncCheckpointDto,
  ) {
    return this.service.acknowledgeCheckpoint(user, deviceId, dto);
  }

  @Get('devices/:deviceId/conflicts')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'List conflicts retained for this account and device for manual review.',
    description: 'Returns only events and incoming payloads owned by the authenticated account.',
  })
  @ApiQuery({ name: 'limit', required: false, type: Number, example: 50 })
  listConflicts(
    @CurrentUser() user: AuthenticatedUser,
    @Param('deviceId', ParseUUIDPipe) deviceId: string,
    @Query('limit') limit?: string,
  ) {
    return this.service.listConflicts(user, deviceId, limit === undefined ? undefined : Number(limit));
  }
}
