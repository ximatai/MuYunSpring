import type {
  BusinessRoutePageDescriptor,
  PageDescriptor,
  PageLayoutMode,
  RoutePageTarget,
} from '@muyun/web-contracts';
import {
  staticRouteDefinitions,
  routePageLoaders,
  type StaticRouteDefinition,
} from '../app/staticRouteDefinitions';

export interface PlatformAdminRoute extends StaticRouteDefinition {
  component: (typeof routePageLoaders)[string];
}

export const platformAdminRoutes: PlatformAdminRoute[] = staticRouteDefinitions.map((definition) => ({
  ...definition,
  component: routePageLoaders[definition.componentPath],
}));
export const platformAdminRoutePrefixes = platformAdminRoutes.map((route) => route.route);
export const platformAdminModuleRoutes = Object.fromEntries(
  platformAdminRoutes
    .filter((route) => route.menuEntry !== false)
    .map((route) => [route.moduleAlias, route.route]),
);
export const platformAdminRouteLayouts: Record<string, PageLayoutMode> = Object.fromEntries(
  platformAdminRoutes.map((route) => [route.route, route.layout]),
);

export function resolvePlatformAdminRoute(
  descriptor?: BusinessRoutePageDescriptor,
): PlatformAdminRoute | undefined {
  if (!descriptor) return undefined;
  return platformAdminRoutes.find((route) => routeMatchesTarget(route, descriptor.target));
}
export function isPlatformAdminRoutePage(
  descriptor?: PageDescriptor,
): descriptor is BusinessRoutePageDescriptor {
  return descriptor?.pageType === 'business-route' && Boolean(resolvePlatformAdminRoute(descriptor));
}
function routeMatchesTarget(route: PlatformAdminRoute, target: RoutePageTarget) {
  return target.route
    ? target.route === route.route &&
        (target.moduleAlias === undefined || target.moduleAlias === route.moduleAlias)
    : target.moduleAlias === route.moduleAlias;
}
