import { BloodGroup } from '@prisma/client';
import { IsBoolean, IsEnum, IsOptional, IsString, MaxLength } from 'class-validator';
import { ApiPropertyOptional } from '@nestjs/swagger';

export class UpdateDonorProfileDto {
  @ApiPropertyOptional({ enum: BloodGroup, nullable: true })
  @IsOptional()
  @IsEnum(BloodGroup)
  bloodGroup?: BloodGroup | null;

  @ApiPropertyOptional({
    example: 'Rumuola',
    maxLength: 120,
    nullable: true,
    description: 'General community only; do not send precise coordinates.',
  })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string | null;

  @ApiPropertyOptional({ example: true })
  @IsOptional()
  @IsBoolean()
  isAvailable?: boolean;
}
