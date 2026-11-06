import { IsEnum } from 'class-validator';
import { UserRole } from '@prisma/client';
import { ApiProperty } from '@nestjs/swagger';

export class SetUserRoleDto {
  @ApiProperty({ enum: UserRole, example: UserRole.DONOR })
  @IsEnum(UserRole)
  role!: UserRole;
}
