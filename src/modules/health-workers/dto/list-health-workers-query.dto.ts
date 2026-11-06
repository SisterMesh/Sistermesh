import { ApiPropertyOptional } from '@nestjs/swagger';
import { HealthWorkerVerificationStatus } from '@prisma/client';
import { IsEnum, IsOptional } from 'class-validator';

export class ListHealthWorkersQueryDto {
  @ApiPropertyOptional({ enum: HealthWorkerVerificationStatus, default: HealthWorkerVerificationStatus.PENDING })
  @IsOptional()
  @IsEnum(HealthWorkerVerificationStatus)
  verificationStatus?: HealthWorkerVerificationStatus;
}
