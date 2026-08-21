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
    route: '/config/dictionaries',
    moduleAlias: 'platform.dictionary_category',
    component: DictionaryManagementView,
    layout: 'workspace',
  },
  {
    route: '/config/menus',
    moduleAlias: 'platform.menu_scheme',
    component: MenuManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/employees',
    moduleAlias: 'iam.employee',
    component: EmployeeManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/users',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/users/form',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/users/form/:userId',
    moduleAlias: 'iam.user',
    component: UserManagementView,
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/system-users',
    moduleAlias: 'iam.system_user',
    component: SystemUserManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/roles',
    moduleAlias: 'iam.role',
    component: RoleManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/role-authorization',
    moduleAlias: 'iam.role',
    component: RoleAuthorizationView,
    layout: 'workspace',
    menuEntry: false,
  },
];

export const platformAdminRoutePrefixes = Array.from(
  new Set(['/_workspace', ...platformAdminRoutes.map((route) => route.route)]),
);
/**
 * Readable legacy URLs that are intentionally delegated to the standard module runner.
 * Module menus without a dedicated static page, including `platform.module`, use the
 * canonical `/platform/dynamic/<moduleAlias>/list` URL directly.
 */
export const platformAdminDynamicModuleRoutes: Record<string, string> = {
  '/config/field-ui-controls': 'platform.field_ui_control',
  // Tenant management is descriptor-owned. Keep the former static URL for
  // bookmarks and menus; page serialization canonicalizes it to the module host.
  '/iam/tenants': 'iam.tenant',
  '/iam/organizations': 'iam.organization',
  '/iam/departments': 'iam.department',
  // Keep the former static module-management URL as a bookmark-compatible
  // dynamic entry. Page serialization still uses the canonical dynamic URL.
  '/config/modules': 'platform.module',
  '/platform/security/passwords': 'iam.password_policy_rule',
};
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
