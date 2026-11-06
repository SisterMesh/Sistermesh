import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { DonorResponsesController } from './donor-responses.controller';
import { DonorResponsesService } from './donor-responses.service';

@Module({
  controllers: [DonorResponsesController],
  providers: [DonorResponsesService, RolesGuard],
  exports: [DonorResponsesService],
})
export class DonorResponsesModule {}
