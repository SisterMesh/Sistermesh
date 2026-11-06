import { FacilityStatus } from '@prisma/client';
import { IsEnum } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class SetFacilityStatusDto {
  @ApiProperty({ enum: FacilityStatus, example: FacilityStatus.ACTIVE })
  @IsEnum(FacilityStatus)
  status!: FacilityStatus;
}
