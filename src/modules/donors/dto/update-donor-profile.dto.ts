import { ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup } from '@prisma/client';
import { IsBoolean, IsEnum, IsOptional, IsString, MaxLength } from 'class-validator';

export class UpdateDonorProfileDto {
  @ApiPropertyOptional({ enum: BloodGroup })
  @IsOptional()
  @IsEnum(BloodGroup)
  bloodGroup?: BloodGroup;

  @ApiPropertyOptional({ maxLength: 120, description: 'General locality or community, not a precise address.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string | null;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isAvailable?: boolean;
}
