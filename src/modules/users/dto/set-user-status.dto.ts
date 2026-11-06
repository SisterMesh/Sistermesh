import { AccountStatus } from '@prisma/client';
import { IsEnum } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class SetUserStatusDto {
  @ApiProperty({ enum: AccountStatus, example: AccountStatus.SUSPENDED })
  @IsEnum(AccountStatus)
  status!: AccountStatus;
}
