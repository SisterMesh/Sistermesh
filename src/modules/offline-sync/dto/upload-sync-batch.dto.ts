import { ApiProperty } from '@nestjs/swagger';
import { ArrayMaxSize, ArrayMinSize, IsArray, IsUUID } from 'class-validator';

export class UploadSyncBatchDto {
  @ApiProperty({ format: 'uuid', description: 'Stable client-generated batch UUID; reuse for retries.' })
  @IsUUID()
  clientBatchId!: string;

  @ApiProperty({
    type: 'array',
    maxItems: 100,
    description: 'Each event is validated and processed independently.',
    items: { type: 'object', additionalProperties: true },
  })
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(100)
  events!: unknown[];
}
