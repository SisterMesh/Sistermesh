import {
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform } from 'class-transformer';

export class CreateFacilityDto {
  @ApiProperty({ example: 'Port Harcourt Women’s Hospital', maxLength: 160 })
  @Transform(({ value }) => (typeof value === 'string' ? value.trim() : value))
  @IsString()
  @MinLength(2)
  @MaxLength(160)
  name!: string;

  @ApiPropertyOptional({ example: 'Hospital', maxLength: 80 })
  @IsOptional()
  @IsString()
  @MaxLength(80)
  facilityType?: string;

  @ApiPropertyOptional({ example: 'Rumuola Road', maxLength: 240 })
  @IsOptional()
  @IsString()
  @MaxLength(240)
  address?: string;

  @ApiPropertyOptional({ example: 'Rumuola', maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string;

  @ApiPropertyOptional({ example: 'Obio/Akpor', maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  localGovernmentArea?: string;

  @ApiPropertyOptional({ example: 'Rivers', maxLength: 100 })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  state?: string;

  @ApiPropertyOptional({ example: '+2348012345678' })
  @IsOptional()
  @IsString()
  @Matches(/^\+[1-9]\d{7,14}$/)
  contactPhone?: string;
}
