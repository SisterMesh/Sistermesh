import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { DonorResponsesModule } from '../donor-responses/donor-responses.module';
import { EmergencyRequestsModule } from '../emergency-requests/emergency-requests.module';
import { OfflineSyncController } from './offline-sync.controller';
import { OfflineSyncService } from './offline-sync.service';

@Module({
  imports: [EmergencyRequestsModule, DonorResponsesModule],
  controllers: [OfflineSyncController],
  providers: [OfflineSyncService, RolesGuard],
})
export class OfflineSyncModule {}
