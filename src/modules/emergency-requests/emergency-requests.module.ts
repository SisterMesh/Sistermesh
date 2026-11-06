import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { EmergencyRequestsController } from './emergency-requests.controller';
import { EmergencyRequestsService } from './emergency-requests.service';

@Module({
  controllers: [EmergencyRequestsController],
  providers: [EmergencyRequestsService, RolesGuard],
  exports: [EmergencyRequestsService],
})
export class EmergencyRequestsModule {}
