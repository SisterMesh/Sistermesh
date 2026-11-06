import { ApiProperty } from '@nestjs/swagger';
import { FacilityStatus } from '@prisma/client';
import { IsIn } from 'class-validator';

export class UpdateFacilityStatusDto {
  @ApiProperty({ enum: [FacilityStatus.ACTIVE, FacilityStatus.INACTIVE] })
  @IsIn([FacilityStatus.ACTIVE, FacilityStatus.INACTIVE])
  status!: FacilityStatus;
}
