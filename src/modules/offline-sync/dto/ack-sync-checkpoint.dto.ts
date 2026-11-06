import { ApiProperty } from '@nestjs/swagger';
import { IsString, Matches, MaxLength } from 'class-validator';

export class AckSyncCheckpointDto {
  @ApiProperty({
    type: String,
    description: 'The exact nextCursor returned by the last changes page, encoded as a decimal string.',
    example: '123',
  })
  @IsString()
  @Matches(/^\d+$/)
  @MaxLength(20)
  cursor!: string;
}
