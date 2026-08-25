import { defineAsyncComponent, type Component } from 'vue';
import type {
  BusinessRoutePageDescriptor,
  PageDescriptor,
  PageLayoutMode,
  RoutePageTarget,
} from '@muyun/web-contracts';
const RoleManagementView = defineAsyncComponent(() => import('../views/RoleManagementView.vue'));
const RoleAuthorizationView = defineAsyncComponent(() => import('../views/RoleAuthorizationView.vue'));

export interface PlatformAdminRoute {
  route: string;
  moduleAlias: string;
  component: Component;
  /** Page layout contract resolved before the route enters the workbench. */
  layout: PageLayoutMode;
  /** Internal pages share a module context but must not replace that module's menu route. */
  menuEntry?: boolean;
}

export const platformAdminRoutes: PlatformAdminRoute[] = [
  {
    route: '/iam/role',
    moduleAlias: 'iam.role',
    component: RoleManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/role/authorization',
    moduleAlias: 'iam.role',
    component: RoleAuthorizationView,
    layout: 'workspace',
    menuEntry: false,
  },
];

export const platformAdminRoutePrefixes = Array.from(
  new Set(['/_platform/workspace', ...platformAdminRoutes.map((route) => route.route)]),
);
export const platformAdminModuleRoutes = Object.fromEntries(
  platformAdminRoutes
    .filter((route) => route.menuEntry !== false)
    .map((route) => [route.moduleAlias, route.route]),
);
export const platformAdminRouteLayouts = Object.fromEntries(
  platformAdminRoutes.map((route) => [route.route, route.layout]),
);

export function resolvePlatformAdminRoute(
  descriptor?: BusinessRoutePageDescriptor,
): PlatformAdminRoute | undefined {
  if (!descriptor) {
    return undefined;
  }
  return platformAdminRoutes.find((route) => routeMatchesTarget(route, descriptor.target));
}

export function isPlatformAdminRoutePage(
  descriptor?: PageDescriptor,
): descriptor is BusinessRoutePageDescriptor {
  return descriptor?.pageType === 'business-route' && Boolean(resolvePlatformAdminRoute(descriptor));
}

function routeMatchesTarget(route: PlatformAdminRoute, target: RoutePageTarget) {
  if (target.route) {
    return (
      routePathMatches(route.route, target.route) &&
      (target.moduleAlias === undefined || target.moduleAlias === route.moduleAlias)
    );
  }
  return target.moduleAlias === route.moduleAlias;
}

function routePathMatches(pattern: string, path: string) {
  const patternSegments = pattern.split('/').filter(Boolean);
  const pathSegments = path.split('/').filter(Boolean);
  return (
    patternSegments.length === pathSegments.length &&
    patternSegments.every((segment, index) => segment.startsWith(':') || segment === pathSegments[index])
  );
}
