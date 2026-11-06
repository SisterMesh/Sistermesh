import { ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup, RequestUrgency } from '@prisma/client';
import { IsDateString, IsEnum, IsInt, IsOptional, Max, Min } from 'class-validator';

export class UpdateEmergencyRequestDto {
  @ApiPropertyOptional({ enum: BloodGroup })
  @IsOptional()
  @IsEnum(BloodGroup)
  requestedBloodGroup?: BloodGroup;

  @ApiPropertyOptional({ enum: RequestUrgency })
  @IsOptional()
  @IsEnum(RequestUrgency)
  urgency?: RequestUrgency;

  @ApiPropertyOptional({ minimum: 1, maximum: 100, description: 'Required number of confirmed donations.' })
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(100)
  units?: number;

  @ApiPropertyOptional({ format: 'date-time', nullable: true, description: 'Must be in the future; null removes the expiry.' })
  @IsOptional()
  @IsDateString()
  expiresAt?: string | null;
}
