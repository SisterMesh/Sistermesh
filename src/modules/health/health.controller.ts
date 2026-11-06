import { Controller, Get, HttpException, HttpStatus } from '@nestjs/common';
import { ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { PublicRoute } from '../../common/public-route.decorator';
import { HealthService } from './health.service';

@ApiTags('health')
@Controller('health')
export class HealthController {
  constructor(private readonly healthService: HealthService) {}

  @Get()
  @PublicRoute()
  @ApiOperation({ summary: 'Check API and database availability' })
  @ApiResponse({ status: 200, description: 'API and database are available' })
  @ApiResponse({ status: 503, description: 'Database is unavailable' })
  async check() {
    const result = await this.healthService.check();
    if (result.status === 'error') {
      throw new HttpException(result, HttpStatus.SERVICE_UNAVAILABLE);
    }
    return result;
  }
}
