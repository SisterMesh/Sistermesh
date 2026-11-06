import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsOptional, IsString, Matches, MaxLength, MinLength } from 'class-validator';

export class RegisterHealthWorkerDto {
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

  @ApiPropertyOptional({ maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  designation?: string;

  @ApiPropertyOptional({ maxLength: 120, description: 'Professional ID is subject to administrator verification.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  professionalId?: string;
}
