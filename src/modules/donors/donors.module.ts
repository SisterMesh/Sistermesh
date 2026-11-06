import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { DonorsController } from './donors.controller';
import { DonorsService } from './donors.service';

@Module({
  controllers: [DonorsController],
  providers: [DonorsService, RolesGuard],
  exports: [DonorsService],
})
export class DonorsModule {}
