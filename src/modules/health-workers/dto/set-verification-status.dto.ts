import { HealthWorkerVerificationStatus } from '@prisma/client';
import { IsEnum } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class SetVerificationStatusDto {
  @ApiProperty({ enum: HealthWorkerVerificationStatus, example: 'VERIFIED' })
  @IsEnum(HealthWorkerVerificationStatus)
  status!: HealthWorkerVerificationStatus;
}
