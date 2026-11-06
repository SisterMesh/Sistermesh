import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { DonationConfirmationsController } from './donation-confirmations.controller';
import { DonationConfirmationsService } from './donation-confirmations.service';

@Module({
  controllers: [DonationConfirmationsController],
  providers: [DonationConfirmationsService, RolesGuard],
})
export class DonationConfirmationsModule {}
