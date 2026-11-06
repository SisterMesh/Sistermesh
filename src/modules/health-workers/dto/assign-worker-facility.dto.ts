import { ApiProperty } from '@nestjs/swagger';
import { IsUUID } from 'class-validator';

export class AssignWorkerFacilityDto {
  @ApiProperty({ format: 'uuid' })
  @IsUUID()
  facilityId!: string;
}
