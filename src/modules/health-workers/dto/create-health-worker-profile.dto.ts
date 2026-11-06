import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsOptional, IsString, MaxLength } from 'class-validator';

export class CreateHealthWorkerProfileDto {
  @ApiPropertyOptional({ maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  designation?: string;

  @ApiPropertyOptional({ maxLength: 120, description: 'Requires administrator verification.' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  professionalId?: string;
}
