import { Body, Controller, Get, Patch, UseGuards } from '@nestjs/common';
import { UserRole } from '@prisma/client';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiResponse,
  ApiTags,
} from '@nestjs/swagger';
import { CurrentUser } from '../../common/auth/current-user.decorator';
import { AuthenticatedUser } from '../../common/auth/authenticated-user';
import { JwtAuthGuard } from '../../common/auth/jwt-auth.guard';
import { Roles } from '../../common/auth/roles.decorator';
import { RolesGuard } from '../../common/auth/roles.guard';
import { UpdateDonorProfileDto } from './dto/update-donor-profile.dto';
import { DonorsService } from './donors.service';

@ApiTags('donors')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles(UserRole.DONOR)
@Controller('donors')
export class DonorsController {
  constructor(private readonly donorsService: DonorsService) {}

  @Get('me')
  @ApiOperation({ summary: 'Get the authenticated donor profile' })
  @ApiResponse({ status: 200, description: 'Donor profile; precise location is not stored' })
  @ApiResponse({ status: 403, description: 'Donor role required' })
  getOwnProfile(@CurrentUser() user: AuthenticatedUser) {
    return this.donorsService.getOwnProfile(user.id);
  }

  @Patch('me')
  @ApiOperation({ summary: 'Update donor blood group, locality, or availability' })
  @ApiResponse({ status: 200, description: 'Updated donor profile' })
  @ApiResponse({ status: 400, description: 'Invalid profile details' })
  updateOwnProfile(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateDonorProfileDto,
  ) {
    return this.donorsService.updateOwnProfile(user.id, dto);
  }
}
