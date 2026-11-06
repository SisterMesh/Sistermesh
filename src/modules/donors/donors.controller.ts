import { Body, Controller, Get, Patch, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateDonorProfileDto } from './dto/create-donor-profile.dto';
import { UpdateDonorProfileDto } from './dto/update-donor-profile.dto';
import { DonorsService } from './donors.service';

@ApiTags('Donor profiles')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.DONOR)
@Controller('donors')
export class DonorsController {
  constructor(private readonly service: DonorsService) {}

  @Post('me')
  @ApiOperation({ summary: 'Create the signed-in donor’s profile if it does not already exist.' })
  createMine(@CurrentUser() user: AuthenticatedUser, @Body() dto: CreateDonorProfileDto) {
    return this.service.createMine(user, dto);
  }

  @Get('me')
  @ApiOperation({ summary: 'Retrieve the signed-in donor’s profile.' })
  getMine(@CurrentUser() user: AuthenticatedUser) {
    return this.service.getMine(user);
  }

  @Patch('me')
  @ApiOperation({ summary: 'Update the signed-in donor’s blood group, locality, or availability.' })
  updateMine(@CurrentUser() user: AuthenticatedUser, @Body() dto: UpdateDonorProfileDto) {
    return this.service.updateMine(user, dto);
  }
}
