import { IsEnum } from 'class-validator';
import { UserRole } from '@prisma/client';
import { ApiProperty } from '@nestjs/swagger';

export class SetUserRoleDto {
  @ApiProperty({ enum: UserRole, example: UserRole.INDIVIDUAL })
  @IsEnum(UserRole)
  role!: UserRole;
}
