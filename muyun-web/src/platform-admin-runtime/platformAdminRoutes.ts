import { defineAsyncComponent, type Component } from 'vue';
import type {
  BusinessRoutePageDescriptor,
  PageDescriptor,
  PageLayoutMode,
  RoutePageTarget,
} from '@muyun/web-contracts';
const DictionaryManagementView = defineAsyncComponent(() => import('../views/DictionaryManagementView.vue'));
const EmployeeManagementView = defineAsyncComponent(() => import('../views/EmployeeManagementView.vue'));
const MenuManagementView = defineAsyncComponent(() => import('../views/MenuManagementView.vue'));
const RoleManagementView = defineAsyncComponent(() => import('../views/RoleManagementView.vue'));
const RoleAuthorizationView = defineAsyncComponent(() => import('../views/RoleAuthorizationView.vue'));
const SystemUserManagementView = defineAsyncComponent(() => import('../views/SystemUserManagementView.vue'));
const UserManagementView = defineAsyncComponent(() => import('../views/UserManagementView.vue'));

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
    route: '/platform/dictionary-category',
    moduleAlias: 'platform.dictionary_category',
    component: DictionaryManagementView,
    layout: 'workspace',
  },
  {
    route: '/platform/menu-scheme',
    moduleAlias: 'platform.menu_scheme',
    component: MenuManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/employee',
    moduleAlias: 'iam.employee',
    component: EmployeeManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/user',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/user/form',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/user/form/:userId',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/system-user',
    moduleAlias: 'iam.system_user',
    component: SystemUserManagementView,
    layout: 'workspace',
  },
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
