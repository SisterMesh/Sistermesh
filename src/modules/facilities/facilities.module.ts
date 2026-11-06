import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { PrismaModule } from '../prisma/prisma.module';
import { RolesGuard } from '../../common/roles.guard';
import { AdminFacilitiesController } from './admin-facilities.controller';
import { FacilitiesController } from './facilities.controller';
import { FacilitiesService } from './facilities.service';

@Module({
  imports: [AuthModule, PrismaModule],
  controllers: [FacilitiesController, AdminFacilitiesController],
  providers: [FacilitiesService, RolesGuard],
  exports: [FacilitiesService],
})
export class FacilitiesModule {}
