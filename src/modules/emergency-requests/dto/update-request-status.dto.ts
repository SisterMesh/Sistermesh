import { ApiProperty } from '@nestjs/swagger';
import { EmergencyRequestStatus } from '@prisma/client';
import { IsIn } from 'class-validator';

export class UpdateRequestStatusDto {
  @ApiProperty({ enum: [EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.WAITING, EmergencyRequestStatus.ACCEPTED, EmergencyRequestStatus.COMPLETED, EmergencyRequestStatus.EXPIRED] })
  @IsIn([EmergencyRequestStatus.SEARCHING, EmergencyRequestStatus.WAITING, EmergencyRequestStatus.ACCEPTED, EmergencyRequestStatus.COMPLETED, EmergencyRequestStatus.EXPIRED])
  status!: EmergencyRequestStatus;
}
