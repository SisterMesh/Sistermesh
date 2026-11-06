import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { PrismaModule } from '../prisma/prisma.module';
import { AdminHealthWorkersController } from './admin-health-workers.controller';
import { HealthWorkersController } from './health-workers.controller';
import { HealthWorkersService } from './health-workers.service';

@Module({
  imports: [AuthModule, PrismaModule],
  controllers: [HealthWorkersController, AdminHealthWorkersController],
  providers: [HealthWorkersService],
})
export class HealthWorkersModule {}
