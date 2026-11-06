import { ApiProperty } from '@nestjs/swagger';
import { HealthWorkerVerificationStatus } from '@prisma/client';
import { IsIn } from 'class-validator';

export class UpdateWorkerVerificationDto {
  @ApiProperty({
    enum: [HealthWorkerVerificationStatus.VERIFIED, HealthWorkerVerificationStatus.REJECTED],
  })
  @IsIn([HealthWorkerVerificationStatus.VERIFIED, HealthWorkerVerificationStatus.REJECTED])
  verificationStatus!: HealthWorkerVerificationStatus;
}
