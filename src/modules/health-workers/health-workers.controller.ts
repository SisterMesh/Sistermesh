import { Body, Controller, Get, Patch, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { RolesGuard } from '../../common/roles.guard';
import { CreateHealthWorkerProfileDto } from './dto/create-health-worker-profile.dto';
import { UpdateHealthWorkerProfileDto } from './dto/update-health-worker-profile.dto';
import { HealthWorkersService } from './health-workers.service';

@ApiTags('Health worker profiles')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('health-workers')
export class HealthWorkersController {
  constructor(private readonly service: HealthWorkersService) {}

  @Post('me')
  @ApiOperation({ summary: 'Create the signed-in health worker’s profile pending verification.' })
  createMine(@CurrentUser() user: AuthenticatedUser, @Body() dto: CreateHealthWorkerProfileDto) {
    return this.service.createMine(user, dto);
  }

  @Get('me')
  @ApiOperation({ summary: 'Retrieve the signed-in health worker’s own profile.' })
  getMine(@CurrentUser() user: AuthenticatedUser) {
    return this.service.getMine(user);
  }

  @Patch('me')
  @ApiOperation({ summary: 'Update the signed-in health worker’s professional profile.' })
  updateMine(@CurrentUser() user: AuthenticatedUser, @Body() dto: UpdateHealthWorkerProfileDto) {
    return this.service.updateMine(user, dto);
  }

  @Get('me/facility')
  @ApiOperation({ summary: 'Retrieve the active facility assigned to the verified health worker.' })
  getMyFacility(@CurrentUser() user: AuthenticatedUser) {
    return this.service.getMyFacility(user);
  }
}
