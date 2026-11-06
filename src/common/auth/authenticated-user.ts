import { UserRole } from '@prisma/client';

export interface AuthenticatedUser {
  id: string;
  role: UserRole;
  displayName: string;
  phoneNumber: string;
}
