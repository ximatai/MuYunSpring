import type { MenuTreeNode } from '@muyun/web-contracts';
import type { Router } from 'vue-router';
import { routePageLoaders, staticRouteDefinitions } from './staticRouteDefinitions';
import { validateAndCompileMenuRoutes } from './menuRouteCompiler';
import { useRouteDiagnosticsStore } from './routeDiagnosticsStore';

/**
 * 当前用户菜单页面的操作集合。
 * 地址管理文件创建它后，用它准备菜单页面或在身份变化时清空旧页面。
 */
export interface MenuRouteRuntime {
  ensureMenuRoutes(menus?: MenuTreeNode[]): Promise<void>;
  resetMenuRoutes(): void;
}

/**
 * 创建当前用户菜单页面准备器所需的信息。
 * 调用方必须传入同一个地址管理器、工作台页面名称和菜单获取方法，保证检查与页面登记使用的是同一份对象。
 */
export interface CreateMenuRouteRuntimeOptions {
  router: Router;
  workbenchRouteName: string;
  loadMenus(): Promise<MenuTreeNode[]>;
}

/**
 * 管理当前用户可打开菜单页面的准备和清理。
 * 首次进入需要登录的地址时先取菜单、检查菜单并登记正确页面；退出或切换身份后清空这些结果。
 */
export function createMenuRouteRuntime(options: CreateMenuRouteRuntimeOptions): MenuRouteRuntime {
  let menuRoutesReady = false;
  let menuRouteInitialization: Promise<void> | undefined;
  let removeMenuRoutes: Array<() => void> = [];

  /**
   * 进入需要登录的地址前，确保当前用户的菜单页面已经准备好。
   * 公开页面直接放行；准备完成后回到用户原来输入的完整地址。
   */
  function installMenuRouteGuard() {
    options.router.beforeEach(async (to) => {
      if (to.meta.public === true || menuRoutesReady) return true;
      await ensureMenuRoutes();
      return to.fullPath;
    });
  }

  /**
   * 确保当前用户的菜单页面已经准备好。
   * 同一时间有多个页面打开请求时共用同一次准备工作；失败后允许下一次重新尝试。
   */
  function ensureMenuRoutes(menus?: MenuTreeNode[]): Promise<void> {
    if (menuRoutesReady) return Promise.resolve();
    menuRouteInitialization ??= initialize(menus).catch((error) => {
      menuRouteInitialization = undefined;
      throw error;
    });
    return menuRouteInitialization;
  }

  /**
   * 取得菜单，检查菜单配置，登记所有正确页面，并保存错误说明。
   * 这里只登记正确页面；错误菜单仍会保留给诊断页面显示原因。
   */
  async function initialize(menus?: MenuTreeNode[]) {
    const sourceMenus = menus ?? (await options.loadMenus());
    const result = validateAndCompileMenuRoutes(sourceMenus, staticRouteDefinitions, routePageLoaders);
    const diagnostics = useRouteDiagnosticsStore();
    diagnostics.replaceIssues(result.issues);
    removeMenuRoutes = result.validRoutes.map((route) =>
      options.router.addRoute(options.workbenchRouteName, route),
    );
    menuRoutesReady = true;
  }

  /**
   * 清空当前用户已登记的菜单页面和错误说明。
   * 退出登录、切换租户或刷新菜单后调用，避免新身份继续看到旧身份的页面入口。
   */
  function resetMenuRoutes() {
    for (const remove of removeMenuRoutes.splice(0)) remove();
    menuRoutesReady = false;
    menuRouteInitialization = undefined;
    useRouteDiagnosticsStore().clear();
  }

  installMenuRouteGuard();
  return { ensureMenuRoutes, resetMenuRoutes };
}
