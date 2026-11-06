import {
  Body,
  Controller,
  Get,
  Param,
  ParseUUIDPipe,
  Patch,
  Query,
  UseGuards,
} from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiParam,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { CurrentUser } from '../../common/auth/current-user.decorator';
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { Roles } from '../../common/auth/roles.decorator';
import { RolesGuard } from '../../common/auth/roles.guard';
import { SetUserRoleDto } from './dto/set-user-role.dto';
import { SetUserStatusDto } from './dto/set-user-status.dto';
import { UsersService } from './users.service';

@ApiTags('admin users')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/users')
export class AdminUsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get()
  @ApiOperation({ summary: 'List user accounts (administrators only)' })
  @ApiResponse({ status: 200, description: 'Safe user account fields' })
  listUsers(@Query('limit') limit?: string) {
    const parsed = Number(limit);
    return this.usersService.listUsers(
      Number.isFinite(parsed) && parsed > 0 ? parsed : 50,
    );
  }

  @Patch(':userId/role')
  @ApiOperation({
    summary: 'Assign a user role (administrators only)',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'userId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Role changed' })
  @ApiResponse({ status: 403, description: 'Administrator role required' })
  @ApiResponse({ status: 409, description: 'The last active administrator cannot be demoted' })
  setRole(
    @CurrentUser() actor: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) targetId: string,
    @Body() dto: SetUserRoleDto,
  ) {
    return this.usersService.setRole(actor.id, targetId, dto.role);
  }

  @Patch(':userId/status')
  @ApiOperation({
    summary: 'Suspend, activate, or deactivate a user',
    description: 'Requires an active administrator access token.',
  })
  @ApiParam({ name: 'userId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Account status changed' })
  @ApiResponse({ status: 409, description: 'The last active administrator cannot be disabled' })
  setStatus(
    @CurrentUser() actor: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) targetId: string,
    @Body() dto: SetUserStatusDto,
  ) {
    return this.usersService.setStatus(actor.id, targetId, dto.status);
  }
}
