import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { BloodGroup } from '@prisma/client';
import { IsEnum, IsOptional, IsString, Matches, MaxLength, MinLength } from 'class-validator';

export class RegisterDonorDto {
  @ApiProperty({ maxLength: 100 })
  @IsString()
  @MaxLength(100)
  displayName!: string;

  @ApiProperty({ example: '+2348012345678', description: 'Use international E.164 format.' })
  @IsString()
  @Matches(/^\+[1-9]\d{7,14}$/)
  phoneNumber!: string;

  @ApiProperty({ minLength: 12, maxLength: 72, writeOnly: true })
  @IsString()
  @MinLength(12)
  @MaxLength(72)
  password!: string;

  @ApiProperty({ enum: BloodGroup })
  @IsEnum(BloodGroup)
  bloodGroup!: BloodGroup;

  @ApiPropertyOptional({ maxLength: 120, description: 'General locality only; do not provide a precise address.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  locality?: string;
}
