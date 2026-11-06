import { Body, Controller, Get, Param, ParseUUIDPipe, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';
import { DonorResponsesService } from './donor-responses.service';

@ApiTags('Donor responses')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Roles(UserRole.DONOR)
@Controller('donor-responses')
export class DonorResponsesController {
  constructor(private readonly service: DonorResponsesService) {}

  @Post(':requestId')
  @ApiOperation({ summary: 'Accept or decline a request once; retry with the same idempotency key.' })
  respond(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: CreateDonorResponseDto,
  ) {
    return this.service.respond(user, requestId, dto);
  }

  @Get('mine')
  @ApiOperation({ summary: 'List the signed-in donor’s responses.' })
  listMine(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listMine(user);
  }
}
