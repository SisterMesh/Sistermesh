import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup, RequestUrgency } from '@prisma/client';
import {
  IsBoolean,
  IsDateString,
  IsEnum,
  IsInt,
  IsOptional,
  IsString,
  IsUUID,
  Max,
  MaxLength,
  Min,
} from 'class-validator';

export class CreateEmergencyRequestDto {
  @ApiProperty({ format: 'uuid', description: 'Stable client-generated retry key.' })
  @IsUUID()
  clientRequestId!: string;

  @ApiPropertyOptional({ format: 'uuid', description: 'Active facility selected by an individual requester.' })
  @IsOptional()
  @IsUUID()
  facilityId?: string;

  @ApiPropertyOptional({ default: true })
  @IsOptional()
  @IsBoolean()
  isForSelf?: boolean;

  @ApiPropertyOptional({ maxLength: 120, description: 'Private coordination detail; never included in donor feeds.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  beneficiaryName?: string;

  @ApiPropertyOptional({ maxLength: 80, description: 'Private relationship to the requester.' })
  @IsOptional()
  @IsString()
  @MaxLength(80)
  beneficiaryRelationship?: string;

  @ApiPropertyOptional({ maxLength: 1000, description: 'Private information for the assigned facility only.' })
  @IsOptional()
  @IsString()
  @MaxLength(1000)
  additionalInfo?: string;

  @ApiProperty({ enum: BloodGroup })
  @IsEnum(BloodGroup)
  requestedBloodGroup!: BloodGroup;

  @ApiProperty({ enum: RequestUrgency })
  @IsEnum(RequestUrgency)
  urgency!: RequestUrgency;

  @ApiProperty({ minimum: 1, maximum: 100, description: 'Required number of confirmed donations.' })
  @IsInt()
  @Min(1)
  @Max(100)
  units!: number;

  @ApiPropertyOptional({ format: 'date-time' })
  @IsOptional()
  @IsDateString()
  expiresAt?: string;
}
