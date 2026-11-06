import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsOptional, IsString, IsUUID, MaxLength } from 'class-validator';

export class RegisterDeviceDto {
  @ApiProperty({ format: 'uuid', description: 'Stable random UUID retained by the client across retries.' })
  @IsUUID()
  clientDeviceId!: string;

  @ApiPropertyOptional({ maxLength: 120 })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  name?: string;

  @ApiPropertyOptional({ maxLength: 4096, description: 'Informational public key; JWT remains the authentication method.' })
  @IsOptional()
  @IsString()
  @MaxLength(4096)
  publicKey?: string;
}
