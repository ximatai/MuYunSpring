import type { Component } from 'vue';
import type { PageLayoutMode } from '@muyun/web-contracts';
import {
  platformAdminDynamicModuleRoutes,
  platformAdminRoutes,
} from '../platform-admin-runtime/platformAdminRoutes';

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
const componentPathByRoute: Record<string, StaticRouteDefinition['componentPath']> = {
  '/config/field-ui-controls': '/src/views/FieldUiControlManagementView.vue',
  '/config/dictionaries': '/src/views/DictionaryManagementView.vue',
  '/config/menus': '/src/views/MenuManagementView.vue',
  '/iam/tenants': '/src/views/TenantManagementView.vue',
  '/iam/employees': '/src/views/EmployeeManagementView.vue',
  '/iam/users': '/src/views/UserManagementView.vue',
  '/iam/users/form': '/src/views/UserManagementView.vue',
  '/iam/users/form/:userId': '/src/views/UserManagementView.vue',
  '/iam/system-users': '/src/views/SystemUserManagementView.vue',
  '/iam/roles': '/src/views/RoleManagementView.vue',
  '/iam/role-authorization': '/src/views/RoleAuthorizationView.vue',
};

const platformStaticRouteDefinitions: StaticRouteDefinition[] = platformAdminRoutes.map((route) => ({
  route: route.route as StaticRouteDefinition['route'],
  moduleAlias: route.moduleAlias,
  componentPath: componentPathByRoute[route.route]!,
  layout: route.layout,
  menuEntry: route.menuEntry,
}));

const dynamicCompatibilityRouteDefinitions: StaticRouteDefinition[] = Object.entries(
  platformAdminDynamicModuleRoutes,
).map(([route, moduleAlias]) => ({
  route: route as StaticRouteDefinition['route'],
  moduleAlias,
  componentPath: '/src/views/DynamicModuleRouteView.vue',
  layout: 'workspace',
}));

export const staticRouteDefinitions: StaticRouteDefinition[] = [
  ...platformStaticRouteDefinitions,
  ...dynamicCompatibilityRouteDefinitions,
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
