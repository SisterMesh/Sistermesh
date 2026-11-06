import { Body, Controller, Param, ParseUUIDPipe, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateDonationConfirmationDto } from './dto/create-donation-confirmation.dto';
import { DonationConfirmationsService } from './donation-confirmations.service';

@ApiTags('Donation confirmations')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.HEALTH_WORKER)
@Controller('emergency-requests/:requestId/donations')
export class DonationConfirmationsController {
  constructor(private readonly service: DonationConfirmationsService) {}

  @Post('confirm')
  @ApiOperation({ summary: 'Record an accepted donor’s completed donation once.' })
  confirm(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: CreateDonationConfirmationDto,
  ) {
    return this.service.confirm(user, requestId, dto);
  }
}
