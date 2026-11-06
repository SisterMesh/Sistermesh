import { Module } from '@nestjs/common';
import { AuthModule } from './modules/auth/auth.module';
import { DonationConfirmationsModule } from './modules/donation-confirmations/donation-confirmations.module';
import { DonorResponsesModule } from './modules/donor-responses/donor-responses.module';
import { DonorsModule } from './modules/donors/donors.module';
import { EmergencyRequestsModule } from './modules/emergency-requests/emergency-requests.module';
import { FacilitiesModule } from './modules/facilities/facilities.module';
import { HealthModule } from './modules/health/health.module';
import { HealthWorkersModule } from './modules/health-workers/health-workers.module';
import { PrismaModule } from './modules/prisma/prisma.module';
import { UsersModule } from './modules/users/users.module';

@Module({
  imports: [
    PrismaModule,
    AuthModule,
    UsersModule,
    DonorsModule,
    HealthWorkersModule,
    FacilitiesModule,
    HealthModule,
    EmergencyRequestsModule,
    DonorResponsesModule,
    DonationConfirmationsModule,
  ],
})
export class AppModule {}
