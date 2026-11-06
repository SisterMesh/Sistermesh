import { SetMetadata } from '@nestjs/common';
import { UserRole } from '@prisma/client';

export const REQUIRED_ROLES = 'required_roles';
export const Roles = (...roles: UserRole[]) => SetMetadata(REQUIRED_ROLES, roles);
