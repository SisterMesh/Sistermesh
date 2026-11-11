import { Module } from '@nestjs/common';
import { AuthModule } from './modules/auth/auth.module';
import { DonorResponsesModule } from './modules/donor-responses/donor-responses.module';
import { DonorsModule } from './modules/donors/donors.module';
import { EmergencyRequestsModule } from './modules/emergency-requests/emergency-requests.module';
import { HealthModule } from './modules/health/health.module';
import { PrismaModule } from './modules/prisma/prisma.module';
import { UsersModule } from './modules/users/users.module';
import { OfflineSyncModule } from './modules/offline-sync/offline-sync.module';

@Module({
  imports: [
    PrismaModule,
    AuthModule,
    UsersModule,
    DonorsModule,
    HealthModule,
    EmergencyRequestsModule,
    DonorResponsesModule,
    OfflineSyncModule,
  ],
})
export class AppModule {}
