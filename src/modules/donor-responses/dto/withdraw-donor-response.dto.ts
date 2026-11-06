import { ApiProperty } from '@nestjs/swagger';
import { IsUUID } from 'class-validator';

export class WithdrawDonorResponseDto {
  @ApiProperty({ format: 'uuid', description: 'Stable retry key for this withdrawal.' })
  @IsUUID()
  idempotencyKey!: string;
}
