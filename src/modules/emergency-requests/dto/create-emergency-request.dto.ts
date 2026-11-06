import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup, RequestUrgency } from '@prisma/client';
import { IsDateString, IsEnum, IsInt, IsOptional, IsUUID, Max, Min } from 'class-validator';

export class CreateEmergencyRequestDto {
  @ApiProperty({ format: 'uuid', description: 'Stable client-generated retry key.' })
  @IsUUID()
  clientRequestId!: string;

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
