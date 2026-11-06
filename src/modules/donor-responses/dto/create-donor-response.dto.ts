import { ApiProperty } from '@nestjs/swagger';
import { DonorResponseStatus } from '@prisma/client';
import { IsIn, IsUUID } from 'class-validator';

export class CreateDonorResponseDto {
  @ApiProperty({ format: 'uuid', description: 'Stable retry key for this response submission.' })
  @IsUUID()
  idempotencyKey!: string;

  @ApiProperty({ enum: [DonorResponseStatus.ACCEPTED, DonorResponseStatus.DECLINED] })
  @IsIn([DonorResponseStatus.ACCEPTED, DonorResponseStatus.DECLINED])
  status!: DonorResponseStatus;
}
