import { IsIn, IsString, Matches, MaxLength, MinLength } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';
import { Transform } from 'class-transformer';

export class RegisterDto {
  @ApiProperty({ example: 'Amina Yusuf', minLength: 2, maxLength: 100 })
  @Transform(({ value }) => (typeof value === 'string' ? value.trim() : value))
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  displayName!: string;

  @ApiProperty({ example: '+2348012345678', description: 'Phone number in E.164 format' })
  @IsString()
  @Matches(/^\+[1-9]\d{7,14}$/, {
    message: 'phoneNumber must use international E.164 format.',
  })
  phoneNumber!: string;

  @ApiProperty({ format: 'password', minLength: 12, maxLength: 128 })
  @IsString()
  @MinLength(12)
  @MaxLength(128)
  password!: string;

  // Public registration intentionally cannot request ADMIN.
  @ApiProperty({ enum: ['DONOR', 'HEALTH_WORKER'], example: 'DONOR' })
  @IsIn(['DONOR', 'HEALTH_WORKER'])
  role!: 'DONOR' | 'HEALTH_WORKER';
}
