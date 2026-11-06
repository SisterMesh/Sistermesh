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
import { ApiBearerAuth, ApiOperation, ApiParam, ApiResponse, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { UpdateAccountStatusDto } from './dto/update-account-status.dto';
import { SetUserRoleDto } from './dto/set-user-role.dto';
import { UsersService } from './users.service';

@ApiTags('User administration')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.ADMIN)
@Controller('admin/users')
export class AdminUsersController {
  constructor(private readonly service: UsersService) {}

  @Get()
  @ApiOperation({ summary: 'List user accounts for administrative review.' })
  @ApiResponse({ status: 200, description: 'Safe account fields only.' })
  listUsers(@Query('limit') limit?: string) {
    const parsed = Number(limit);
    return this.service.listUsers(Number.isFinite(parsed) && parsed > 0 ? parsed : 50);
  }

  @Patch(':userId/role')
  @ApiOperation({ summary: 'Assign an account role; administrators only.' })
  @ApiParam({ name: 'userId', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'Role changed and active sessions revoked.' })
  @ApiResponse({ status: 409, description: 'The last active administrator cannot be demoted.' })
  setRole(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: SetUserRoleDto,
  ) {
    return this.service.setRole(admin, userId, dto.role);
  }

  @Patch(':userId/status')
  @ApiOperation({ summary: 'Change an account status; does not grant or change roles.' })
  updateStatus(
    @CurrentUser() admin: AuthenticatedUser,
    @Param('userId', ParseUUIDPipe) userId: string,
    @Body() dto: UpdateAccountStatusDto,
  ) {
    return this.service.updateAccountStatus(admin, userId, dto);
  }
}
