import type { Component } from 'vue';
import type {
  BusinessRoutePageDescriptor,
  PageDescriptor,
  PageLayoutMode,
  RoutePageTarget,
} from '@muyun/web-contracts';
import FieldSpecManagementView from '../views/FieldSpecManagementView.vue';
import FieldUiControlManagementView from '../views/FieldUiControlManagementView.vue';
import DepartmentManagementView from '../views/DepartmentManagementView.vue';
import DictionaryManagementView from '../views/DictionaryManagementView.vue';
import EmployeeManagementView from '../views/EmployeeManagementView.vue';
import MenuManagementView from '../views/MenuManagementView.vue';
import ModuleManagementView from '../views/ModuleManagementView.vue';
import OrganizationManagementView from '../views/OrganizationManagementView.vue';
import PasswordManagementView from '../views/PasswordManagementView.vue';
import PositionManagementView from '../views/PositionManagementView.vue';
import RoleManagementView from '../views/RoleManagementView.vue';
import RoleAuthorizationView from '../views/RoleAuthorizationView.vue';
import SystemUserManagementView from '../views/SystemUserManagementView.vue';
import TenantManagementView from '../views/TenantManagementView.vue';
import UserManagementView from '../views/UserManagementView.vue';

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
    route: '/config/field-specs',
    moduleAlias: 'platform.field_spec',
    component: FieldSpecManagementView,
    layout: 'workspace',
  },
  {
    route: '/config/field-ui-controls',
    moduleAlias: 'platform.field_ui_control',
    component: FieldUiControlManagementView,
    layout: 'workspace',
  },
  {
    route: '/config/dictionaries',
    moduleAlias: 'platform.dictionary_category',
    component: DictionaryManagementView,
    layout: 'workspace',
  },
  {
    route: '/config/modules',
    moduleAlias: 'platform.module',
    component: ModuleManagementView,
    layout: 'workspace',
  },
  {
    route: '/config/menus',
    moduleAlias: 'platform.menu_scheme',
    component: MenuManagementView,
    layout: 'workspace',
  },
  {
    route: '/platform/security/passwords',
    moduleAlias: 'iam.password_policy_rule',
    component: PasswordManagementView,
    layout: 'flow',
  },
  {
    route: '/iam/tenants',
    moduleAlias: 'iam.tenant',
    component: TenantManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/organizations',
    moduleAlias: 'iam.organization',
    component: OrganizationManagementView,
    layout: 'workspace',
  },
  {
    route: '/iam/departments',
    moduleAlias: 'iam.department',
    component: DepartmentManagementView,
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
  {
    route: '/iam/positions',
    moduleAlias: 'iam.position_category',
    component: PositionManagementView,
    layout: 'workspace',
  },
];

export const platformAdminRoutePrefixes = Array.from(
  new Set(platformAdminRoutes.map((route) => route.route)),
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
      target.route === route.route &&
      (target.moduleAlias === undefined || target.moduleAlias === route.moduleAlias)
    );
  }
  return target.moduleAlias === route.moduleAlias;
}
