import { IsDefined, IsUUID, ValidateIf } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class AssignFacilityDto {
  @ApiProperty({
    format: 'uuid',
    nullable: true,
    description: 'Set to null to remove the current association.',
  })
  @ValidateIf((_object, value) => value !== null)
  @IsDefined()
  @IsUUID('4')
  facilityId!: string | null;
}
