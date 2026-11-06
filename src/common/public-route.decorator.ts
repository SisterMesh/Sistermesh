import { SetMetadata } from '@nestjs/common';

export const IS_PUBLIC_ROUTE_KEY = 'is-public-route';
export const PublicRoute = () => SetMetadata(IS_PUBLIC_ROUTE_KEY, true);
