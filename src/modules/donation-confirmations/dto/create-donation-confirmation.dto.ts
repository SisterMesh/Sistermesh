import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsDateString, IsOptional, IsString, IsUUID, Matches } from 'class-validator';

export class CreateDonationConfirmationDto {
  @ApiProperty({ format: 'uuid', description: 'Stable retry key for this confirmation.' })
  @IsUUID()
  idempotencyKey!: string;

  @ApiProperty({ format: 'uuid' })
  @IsUUID()
  donorProfileId!: string;

  @ApiProperty({ description: 'SHA-256 hex digest of the receipt; do not send receipt contents.' })
  @IsString()
  @Matches(/^[a-fA-F0-9]{64}$/)
  receiptHash!: string;

  @ApiPropertyOptional({ format: 'date-time' })
  @IsOptional()
  @IsDateString()
  consentedAt?: string;
}
