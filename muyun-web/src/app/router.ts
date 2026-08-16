import { RouterView, createRouter, createWebHistory } from 'vue-router';
import { loadAppWorkbenchStartupState } from './appWorkbenchStartup';
import { createMenuRouteRuntime } from './menuRouteRuntime';

/**
 * 浏览器地址与页面的统一入口。
 * 这里定义基础页面，并在首次进入受保护地址前加载菜单、检查菜单和登记当前用户可打开的页面。
 */
export const workbenchRouteName = 'workbench';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: workbenchRouteName,
      component: RouterView,
      children: [
        {
          path: '',
          name: 'workbench-home',
          component: () => import('@/views/HomeView.vue'),
          meta: { public: true, cacheable: false },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'route-diagnostics',
      component: () => import('@/views/RouteDiagnosticsView.vue'),
      meta: { public: false, cacheable: false },
    },
  ],
});

/**
 * 当前用户菜单页面的准备器。
 * 它与本文件创建的地址管理器绑定，保证进入前检查和页面登记始终使用同一个对象。
 */
const menuRouteRuntime = createMenuRouteRuntime({
  router,
  workbenchRouteName,
  loadMenus: async () => (await loadAppWorkbenchStartupState()).menus,
});

/**
 * 准备当前用户可打开的菜单页面。
 * 应用已经拿到菜单时可直接传入，避免再次请求；否则由本文件配置的方法获取菜单。
 */
export const ensureMenuRoutes = menuRouteRuntime.ensureMenuRoutes;

/**
 * 清空当前用户的菜单页面和错误说明。
 * 退出登录、切换身份或刷新菜单后调用，避免旧入口留在浏览器地址管理器中。
 */
export const resetMenuRoutes = menuRouteRuntime.resetMenuRoutes;
