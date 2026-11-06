import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsOptional, IsString, MaxLength } from 'class-validator';

export class UpdateHealthWorkerProfileDto {
  @ApiPropertyOptional({ maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  designation?: string;

  @ApiPropertyOptional({ maxLength: 120, description: 'Changing this after verification resets verification.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  professionalId?: string | null;
}
