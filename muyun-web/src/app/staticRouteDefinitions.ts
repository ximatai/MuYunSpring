import type { Component } from 'vue';
import type { PageLayoutMode } from '@muyun/web-contracts';

/**
 * 集中列出前端自带页面的固定地址、所属模块和页面文件。
 * 菜单只能按这里登记的地址打开前端自带页面，不能只凭模块名猜测要显示哪个页面。
 */
export interface StaticRouteDefinition {
  route: `/${string}`;
  moduleAlias: string;
  componentPath: `/src/views/${string}.vue`;
  layout: PageLayoutMode;
  menuEntry?: boolean;
}

export type RoutePageLoader = () => Promise<Component>;

/**
 * 记录允许按需加载的页面文件。
 * 用户真正打开某个页面前，不会下载该页面的代码，避免启动时把所有页面一次性加载进来。
 */
export const routePageLoaders = import.meta.glob('/src/views/**/*View.vue', {
  import: 'default',
}) as Record<string, RoutePageLoader>;

/**
 * 前端自带页面的唯一清单。
 * 每一项把浏览器地址、模块名、页面文件和展示样式放在一起，供菜单检查和页面打开共同使用。
 */
export const staticRouteDefinitions: StaticRouteDefinition[] = [
  {
    route: '/config/applications',
    moduleAlias: 'platform.application',
    componentPath: '/src/views/ApplicationManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/config/field-specs',
    moduleAlias: 'platform.field_spec',
    componentPath: '/src/views/FieldSpecManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/config/field-ui-controls',
    moduleAlias: 'platform.field_ui_control',
    componentPath: '/src/views/FieldUiControlManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/config/dictionaries',
    moduleAlias: 'platform.dictionary_category',
    componentPath: '/src/views/DictionaryManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/config/modules',
    moduleAlias: 'platform.module',
    componentPath: '/src/views/ModuleManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/config/menus',
    moduleAlias: 'platform.menu_scheme',
    componentPath: '/src/views/MenuManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/platform/security/passwords',
    moduleAlias: 'iam.password_policy_rule',
    componentPath: '/src/views/PasswordManagementView.vue',
    layout: 'flow',
  },
  {
    route: '/iam/tenants',
    moduleAlias: 'iam.tenant',
    componentPath: '/src/views/TenantManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/organizations',
    moduleAlias: 'iam.organization',
    componentPath: '/src/views/OrganizationManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/departments',
    moduleAlias: 'iam.department',
    componentPath: '/src/views/DepartmentManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/employees',
    moduleAlias: 'iam.employee',
    componentPath: '/src/views/EmployeeManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/users',
    moduleAlias: 'iam.user',
    componentPath: '/src/views/UserManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/users/form',
    moduleAlias: 'iam.user',
    componentPath: '/src/views/UserManagementView.vue',
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/users/form/:userId',
    moduleAlias: 'iam.user',
    componentPath: '/src/views/UserManagementView.vue',
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/system-users',
    moduleAlias: 'iam.system_user',
    componentPath: '/src/views/SystemUserManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/roles',
    moduleAlias: 'iam.role',
    componentPath: '/src/views/RoleManagementView.vue',
    layout: 'workspace',
  },
  {
    route: '/iam/role-authorization',
    moduleAlias: 'iam.role',
    componentPath: '/src/views/RoleAuthorizationView.vue',
    layout: 'workspace',
    menuEntry: false,
  },
  {
    route: '/iam/positions',
    moduleAlias: 'iam.position_category',
    componentPath: '/src/views/PositionManagementView.vue',
    layout: 'workspace',
  },
];

/**
 * 根据浏览器地址生成稳定的内部名称。
 * 同一个地址始终得到同一个名称，避免不同页面在注册时互相覆盖。
 */
export function createStaticRouteName(route: string): string {
  return `static:${route
    .slice(1)
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-|-$/g, '')}`;
}
