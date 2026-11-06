import { ApiProperty } from '@nestjs/swagger';
import { IsDateString } from 'class-validator';
import { UpdateEmergencyRequestDto } from '../../emergency-requests/dto/update-emergency-request.dto';

export class SyncRequestUpdatePayloadDto extends UpdateEmergencyRequestDto {
  @ApiProperty({
    format: 'date-time',
    description: 'Server updatedAt value observed by the client; mismatch is returned as a conflict.',
  })
  @IsDateString()
  expectedUpdatedAt!: string;
}
