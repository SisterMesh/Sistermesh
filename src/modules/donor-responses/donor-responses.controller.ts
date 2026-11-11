import { Body, Controller, Get, Param, ParseUUIDPipe, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { UserRole } from '@prisma/client';
import { AuthenticatedUser, CurrentUser } from '../../common/authenticated-user';
import { Roles } from '../../common/roles.decorator';
import { RolesGuard } from '../../common/roles.guard';
import { CreateDonorResponseDto } from './dto/create-donor-response.dto';
import { WithdrawDonorResponseDto } from './dto/withdraw-donor-response.dto';
import { DonorResponsesService } from './donor-responses.service';

@ApiTags('Donor responses')
@ApiBearerAuth()
@UseGuards(RolesGuard)
@Controller('donor-responses')
export class DonorResponsesController {
  constructor(private readonly service: DonorResponsesService) {}

  @Post(':requestId')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'Accept or decline a request once; retry with the same idempotency key.' })
  @ApiResponse({ status: 409, description: 'The request is closed or this donor already responded.' })
  respond(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: CreateDonorResponseDto,
  ) {
    return this.service.respond(user, requestId, dto);
  }

  @Get('mine')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({ summary: 'List the signed-in donor’s responses.' })
  listMine(@CurrentUser() user: AuthenticatedUser) {
    return this.service.listMine(user);
  }

  @Post(':requestId/withdraw')
  @Roles(UserRole.INDIVIDUAL)
  @ApiOperation({
    summary: 'Withdraw the signed-in donor’s accepted response while the request remains open.',
    description: 'Retry safely using the same withdrawal idempotency key.',
  })
  @ApiResponse({ status: 409, description: 'The response is not accepted or the request is closed.' })
  withdraw(
    @CurrentUser() user: AuthenticatedUser,
    @Param('requestId', ParseUUIDPipe) requestId: string,
    @Body() dto: WithdrawDonorResponseDto,
  ) {
    return this.service.withdraw(user, requestId, dto);
  }

}
