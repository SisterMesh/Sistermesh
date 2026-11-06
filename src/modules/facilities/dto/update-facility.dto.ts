import {
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';
import { ApiPropertyOptional } from '@nestjs/swagger';
import { Transform } from 'class-transformer';

export class UpdateFacilityDto {
  @ApiPropertyOptional({ example: 'Port Harcourt Women’s Hospital', maxLength: 160 })
  @Transform(({ value }) => (typeof value === 'string' ? value.trim() : value))
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(160)
  name?: string;

  @ApiPropertyOptional({ example: 'Hospital', maxLength: 80, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(80)
  facilityType?: string | null;

  @ApiPropertyOptional({ example: 'Rumuola Road', maxLength: 240, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(240)
  address?: string | null;

  @ApiPropertyOptional({ example: 'Rumuola', maxLength: 120, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string | null;

  @ApiPropertyOptional({ example: 'Obio/Akpor', maxLength: 120, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  localGovernmentArea?: string | null;

  @ApiPropertyOptional({ example: 'Rivers', maxLength: 100, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  state?: string | null;

  @ApiPropertyOptional({ example: '+2348012345678', nullable: true })
  @IsOptional()
  @IsString()
  @Matches(/^\+[1-9]\d{7,14}$/)
  contactPhone?: string | null;
}
