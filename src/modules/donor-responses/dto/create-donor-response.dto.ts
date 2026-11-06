import { ApiProperty } from '@nestjs/swagger';
import { DonorResponseStatus } from '@prisma/client';
import { IsIn, IsUUID } from 'class-validator';

export class CreateDonorResponseDto {
  @ApiProperty({ format: 'uuid', description: 'Stable retry key for this response submission.' })
  @IsUUID()
  idempotencyKey!: string;

  @ApiProperty({
    enum: [DonorResponseStatus.ACCEPTED, DonorResponseStatus.DECLINED],
    description: 'This is a preliminary response, not confirmation of a completed donation.',
    example: DonorResponseStatus.ACCEPTED,
  })
  @IsIn([DonorResponseStatus.ACCEPTED, DonorResponseStatus.DECLINED])
  status!: DonorResponseStatus;
}
