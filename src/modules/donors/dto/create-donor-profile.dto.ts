import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup } from '@prisma/client';
import { IsEnum, IsOptional, IsString, MaxLength } from 'class-validator';

export class CreateDonorProfileDto {
  @ApiProperty({ enum: BloodGroup })
  @IsEnum(BloodGroup)
  bloodGroup!: BloodGroup;

  @ApiPropertyOptional({ maxLength: 120, description: 'General locality or community, not a precise address.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string;
}
