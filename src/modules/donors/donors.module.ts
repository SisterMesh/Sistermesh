import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { PrismaModule } from '../prisma/prisma.module';
import { DonorsController } from './donors.controller';
import { DonorsService } from './donors.service';

@Module({
  imports: [AuthModule, PrismaModule],
  controllers: [DonorsController],
  providers: [DonorsService],
})
export class DonorsModule {}
