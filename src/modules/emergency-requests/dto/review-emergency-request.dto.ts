import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsDateString, IsIn, IsOptional, IsString, MaxLength } from 'class-validator';

export class ReviewEmergencyRequestDto {
  @ApiProperty({ enum: ['APPROVE', 'REJECT', 'REQUEST_INFORMATION'] })
  @IsIn(['APPROVE', 'REJECT', 'REQUEST_INFORMATION'])
  action!: 'APPROVE' | 'REJECT' | 'REQUEST_INFORMATION';

  @ApiProperty({ format: 'date-time', description: 'Request updatedAt last observed by the reviewing device.' })
  @IsDateString()
  expectedUpdatedAt!: string;

  @ApiPropertyOptional({ maxLength: 500, description: 'Required when rejecting or requesting information.' })
  @IsOptional()
  @IsString()
  @MaxLength(500)
  reason?: string;
}
