import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsDateString, IsOptional, IsString, MaxLength } from 'class-validator';
import { UpdateEmergencyRequestDto } from './update-emergency-request.dto';

export class UpdateRequestSubmissionDto extends UpdateEmergencyRequestDto {
  @ApiPropertyOptional({ format: 'date-time' })
  @IsDateString()
  expectedUpdatedAt!: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isForSelf?: boolean;

  @ApiPropertyOptional({ maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  beneficiaryName?: string;

  @ApiPropertyOptional({ maxLength: 80 })
  @IsOptional()
  @IsString()
  @MaxLength(80)
  beneficiaryRelationship?: string;

  @ApiPropertyOptional({ maxLength: 1000 })
  @IsOptional()
  @IsString()
  @MaxLength(1000)
  additionalInfo?: string;
}
