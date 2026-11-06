import { Module } from '@nestjs/common';
import { RolesGuard } from '../../common/roles.guard';
import { AdminHealthWorkersController } from './admin-health-workers.controller';
import { HealthWorkersController } from './health-workers.controller';
import { HealthWorkersService } from './health-workers.service';

@Module({
  controllers: [HealthWorkersController, AdminHealthWorkersController],
  providers: [HealthWorkersService, RolesGuard],
  exports: [HealthWorkersService],
})
export class HealthWorkersModule {}
