import {
  createLanguageContext,
  codeOperationErrorMessage,
  type OperationErrorMessage,
} from '@muyun/web-core';
import type { Component } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import type { MenuEntryType, MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import { canonicalDynamicModulePath } from '@muyun/platform-workbench';
import {
  createStaticRouteName,
  type RoutePageLoader,
  type StaticRouteDefinition,
} from './staticRouteDefinitions';

/**
 * 负责把菜单数据检查后整理成可打开的页面地址。
 * 它只做数据检查和结果整理，不请求后台、不写页面状态，也不直接跳转浏览器。
 */
export type RouteConfigurationIssueCode =
  | 'MENU_ENTRY_TYPE_MISSING'
  | 'MENU_ROUTE_MISSING'
  | 'ROUTE_NOT_REGISTERED'
  | 'MODULE_ALIAS_MISMATCH'
  | 'COMPONENT_NOT_FOUND'
  | 'ENTRY_FIELDS_CONFLICT'
  | 'ROUTE_CONFLICT';

export interface RouteConfigurationIssue {
  code: RouteConfigurationIssueCode;
  menuId?: string;
  menuTitle?: string;
  entryType?: MenuEntryType;
  route?: string;
  componentPath?: string;
  actual?: { entryType?: MenuEntryType; moduleAlias?: string; route?: string; externalUrl?: string };
  expected?: { entryType?: MenuEntryType; moduleAlias?: string; route?: string; componentPath?: string };
  reason: string;
  reasonText?: OperationErrorMessage;
  suggestion?: string;
  suggestionText?: OperationErrorMessage;
}

export interface CompiledMenuRoute {
  menu: MenuRecord;
  route: RouteRecordRaw;
}

export interface MenuRouteValidationResult {
  validRoutes: RouteRecordRaw[];
  compiledRoutes: CompiledMenuRoute[];
  windowMenus: MenuRecord[];
  issues: RouteConfigurationIssue[];
}

/**
 * 检查全部菜单和前端页面清单，并产出可打开的页面、需新窗口打开的菜单和配置问题。
 * 一条菜单有问题不会影响其他正确菜单；有问题的菜单不会被猜测成其他页面。
 */
export function validateAndCompileMenuRoutes(
  menus: MenuTreeNode[] | MenuRecord[],
  definitions: StaticRouteDefinition[],
  componentLoaders: Record<string, RoutePageLoader>,
): MenuRouteValidationResult {
  const issues: RouteConfigurationIssue[] = [];
  const definitionByRoute = new Map<string, StaticRouteDefinition>();
  const names = new Set<string>();
  for (const definition of definitions) {
    const name = createStaticRouteName(definition.route);
    if (!isInternalRoute(definition.route) || definitionByRoute.has(definition.route) || names.has(name)) {
      issues.push(
        issue(
          'ROUTE_CONFLICT',
          undefined,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.conflicting-static-route-declaration-value',
            '静态路由声明冲突：{value1}',
            { value1: definition.route },
          ),
          definition,
        ),
      );
      continue;
    }
    names.add(name);
    definitionByRoute.set(definition.route, definition);
    if (!componentLoaders[definition.componentPath]) {
      issues.push(
        issue(
          'COMPONENT_NOT_FOUND',
          undefined,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.the-declared-vue-page-file-does-not-exist-value',
            '声明的 Vue 页面文件不存在：{value1}',
            { value1: definition.componentPath },
          ),
          definition,
        ),
      );
    }
  }

  const compiledRoutes: CompiledMenuRoute[] = [];
  const windowMenus: MenuRecord[] = [];
  for (const menu of flatten(menus)) {
    const validation = validateMenu(menu, definitionByRoute, componentLoaders);
    if (validation.issue) {
      issues.push(validation.issue);
      continue;
    }
    if (validation.window) {
      windowMenus.push(menu);
      continue;
    }
    if (validation.route) compiledRoutes.push({ menu, route: validation.route });
  }

  const grouped = new Map<string, CompiledMenuRoute[]>();
  for (const item of compiledRoutes) {
    const path = item.route.path;
    grouped.set(path, [...(grouped.get(path) ?? []), item]);
  }
  const rejected = new Set<string>();
  for (const [path, entries] of grouped) {
    const baseline = routeSignature(entries[0]!.route);
    if (entries.some((entry) => routeSignature(entry.route) !== baseline)) {
      rejected.add(path);
      for (const entry of entries)
        issues.push(
          issue(
            'ROUTE_CONFLICT',
            entry.menu,
            declaredRouteText(
              'platform.screen.menuRouteCompiler.multiple-menus-have-conflicting-configurations-for-the-same-path-value',
              '多个菜单对同一路径配置不一致：{value1}',
              { value1: path },
            ),
          ),
        );
    }
  }
  const accepted = compiledRoutes.filter((entry) => !rejected.has(entry.route.path));
  const relatedRoutes = definitions
    .filter(
      (definition) =>
        definition.menuEntry === false &&
        accepted.some((entry) => entry.route.meta?.moduleAlias === definition.moduleAlias) &&
        componentLoaders[definition.componentPath],
    )
    .map(
      (definition) =>
        ({
          path: definition.route,
          name: createStaticRouteName(definition.route),
          component: componentLoaders[definition.componentPath] as () => Promise<Component>,
          meta: {
            entryType: 'route',
            moduleAlias: definition.moduleAlias,
            layout: definition.layout,
            componentPath: definition.componentPath,
            cacheable: true,
          },
        }) satisfies RouteRecordRaw,
    );
  const routesByPath = new Map<string, RouteRecordRaw>();
  for (const entry of accepted) routesByPath.set(entry.route.path, entry.route);
  for (const route of relatedRoutes) routesByPath.set(route.path, route);
  const uniqueRoutes = Array.from(routesByPath.values());
  return { validRoutes: uniqueRoutes, compiledRoutes: accepted, windowMenus, issues };
}

/**
 * 检查单条菜单，并在信息完整时生成它对应的页面地址。
 * 标准模块、前端自带页面、外部链接分别按明确的入口类型处理，不用空字段猜测。
 */
function validateMenu(
  menu: MenuRecord,
  definitions: Map<string, StaticRouteDefinition>,
  loaders: Record<string, RoutePageLoader>,
): { route?: RouteRecordRaw; window?: true; issue?: RouteConfigurationIssue } {
  const fields = [
    menu.moduleAlias,
    menu.route,
    menu.externalUrl,
    menu.openMode,
    menu.pageMode,
    menu.defaultUiConfigId,
    menu.defaultQueryTemplateId,
  ];
  if (!menu.entryType) {
    return fields.some(Boolean)
      ? {
          issue: issue(
            'MENU_ENTRY_TYPE_MISSING',
            menu,
            declaredRouteText(
              'platform.screen.menuRouteCompiler.the-response-has-no-entry-type-the-route-cannot-be',
              '当前接口缺少入口类型，无法安全注册路由',
            ),
            undefined,
            declaredRouteText(
              'platform.screen.menuRouteCompiler.the-server-must-return-entrytype',
              '请由服务端返回 entryType',
            ),
          ),
        }
      : {};
  }
  if (!menu.openMode || !menu.moduleAlias)
    return {
      issue: issue(
        'ENTRY_FIELDS_CONFLICT',
        menu,
        declaredRouteText(
          'platform.screen.menuRouteCompiler.the-entry-must-specify-modulealias-and-openmode',
          '入口必须配置 moduleAlias 与 openMode',
        ),
      ),
    };
  if (menu.entryType === 'module') {
    if (menu.route || menu.externalUrl)
      return {
        issue: issue(
          'ENTRY_FIELDS_CONFLICT',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.a-module-entry-must-not-include-route-or-externalurl',
            'MODULE 入口不得携带 route 或 externalUrl',
          ),
        ),
      };
    const pageMode = menu.pageMode ?? 'LIST';
    const readableDefinition =
      pageMode === 'LIST'
        ? Array.from(definitions.values()).find(
            (definition) =>
              definition.moduleAlias === menu.moduleAlias &&
              definition.componentPath === '/src/views/DynamicModuleRouteView.vue',
          )
        : undefined;
    const path = readableDefinition?.route ?? canonicalDynamicModulePath(menu.moduleAlias);
    const reservedDefinition = definitions.get(path);
    if (reservedDefinition && reservedDefinition.moduleAlias !== menu.moduleAlias) {
      return {
        issue: issue(
          'ROUTE_CONFLICT',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.the-standard-dynamic-module-address-is-occupied-by-a-static',
            '动态模块标准地址已被静态页面占用：{value1}',
            { value1: path },
          ),
          reservedDefinition,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.declare-a-semantic-address-for-this-dynamic-module-or-change',
            '为该动态模块声明语义化地址，或调整冲突的静态页面地址',
          ),
        ),
      };
    }
    return {
      route: {
        path,
        name: `menu:module:${menu.id}`,
        component: () => import('../views/DynamicModuleRouteView.vue'),
        meta: routeMeta(menu, { pageMode }),
      },
    };
  }
  if (menu.entryType === 'route') {
    if (
      !menu.route ||
      !isInternalRoute(menu.route) ||
      menu.externalUrl ||
      menu.pageMode ||
      menu.defaultUiConfigId ||
      menu.defaultQueryTemplateId
    ) {
      return {
        issue: issue(
          'ENTRY_FIELDS_CONFLICT',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.invalid-field-combination-for-a-route-entry',
            'ROUTE 入口字段组合无效',
          ),
        ),
      };
    }
    const definition = definitions.get(menu.route);
    if (!definition)
      return {
        issue: issue(
          'ROUTE_NOT_REGISTERED',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.the-frontend-has-not-registered-route-value',
            '前端未注册 route：{value1}',
            { value1: menu.route },
          ),
          undefined,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.declare-this-route-in-the-static-route-registry',
            '在静态路由注册表中声明该 route',
          ),
        ),
      };
    if (definition.moduleAlias !== menu.moduleAlias)
      return {
        issue: issue(
          'MODULE_ALIAS_MISMATCH',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.the-menu-modulealias-does-not-match-the-frontend-route-declaration',
            '菜单 moduleAlias 与前端路由声明不一致',
          ),
          definition,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.bind-the-menu-to-the-module-required-by-the-frontend',
            '将菜单绑定模块修改为前端路由要求的模块',
          ),
        ),
      };
    const loader = loaders[definition.componentPath];
    if (!loader)
      return {
        issue: issue(
          'COMPONENT_NOT_FOUND',
          menu,
          declaredRouteText(
            'platform.screen.menuRouteCompiler.the-declared-vue-page-file-does-not-exist',
            '声明的 Vue 页面文件不存在',
          ),
          definition,
        ),
      };
    return {
      route: {
        path: definition.route,
        name: createStaticRouteName(definition.route),
        component: loader as () => Promise<Component>,
        meta: { ...routeMeta(menu), layout: definition.layout, componentPath: definition.componentPath },
      },
    };
  }
  if (
    menu.route ||
    menu.pageMode ||
    menu.defaultUiConfigId ||
    menu.defaultQueryTemplateId ||
    !menu.externalUrl
  ) {
    return {
      issue: issue(
        'ENTRY_FIELDS_CONFLICT',
        menu,
        declaredRouteText(
          'platform.screen.menuRouteCompiler.invalid-field-combination-for-a-link-entry',
          'LINK 入口字段组合无效',
        ),
      ),
    };
  }
  if (menu.openMode === 'window') return { window: true };
  return {
    route: {
      path: `/_platform/external/${encodeURIComponent(menu.id)}`,
      name: `menu:link:${menu.id}`,
      component: () => import('../views/ExternalRouteView.vue'),
      meta: routeMeta(menu),
    },
  };
}

/**
 * 把菜单中页面需要的公共信息放到打开后的页面上。
 * 后续页面只读取这里已经确认过的信息，不需要重新解析菜单。
 */
function routeMeta(menu: MenuRecord, extra: Record<string, unknown> = {}) {
  return {
    entryType: menu.entryType,
    menuId: menu.id,
    moduleAlias: menu.moduleAlias,
    title: menu.title,
    externalUrl: menu.externalUrl,
    cacheable: true,
    ...extra,
  };
}

/**
 * 统一生成一条可展示给配置维护人的问题说明。
 * 说明会保留菜单实际配置、前端要求和修正建议，页面无需再次猜测错误原因。
 */
function issue(
  code: RouteConfigurationIssueCode,
  menu?: MenuRecord,
  reason: string | OperationErrorMessage = '',
  definition?: StaticRouteDefinition,
  suggestion?: string | OperationErrorMessage,
): RouteConfigurationIssue {
  return {
    code,
    menuId: menu?.id,
    menuTitle: menu?.title,
    entryType: menu?.entryType,
    route: menu?.route ?? definition?.route,
    componentPath: definition?.componentPath,
    actual: menu
      ? {
          entryType: menu.entryType,
          moduleAlias: menu.moduleAlias,
          route: menu.route,
          externalUrl: menu.externalUrl,
        }
      : undefined,
    expected: definition
      ? {
          entryType: 'route',
          moduleAlias: definition.moduleAlias,
          route: definition.route,
          componentPath: definition.componentPath,
        }
      : undefined,
    reason: typeof reason === 'string' ? reason : reason.message,
    ...(typeof reason === 'string' ? {} : { reasonText: reason }),
    suggestion: typeof suggestion === 'string' || suggestion === undefined ? suggestion : suggestion.message,
    ...(typeof suggestion === 'object' ? { suggestionText: suggestion } : {}),
  };
}

/**
 * 把有层级的菜单摊平成逐条菜单，方便逐项检查。
 */
function flatten(nodes: MenuTreeNode[] | MenuRecord[]): MenuRecord[] {
  return nodes.flatMap((node) => ('record' in node ? [node.record, ...flatten(node.children)] : [node]));
}

/**
 * 判断一个地址是否属于本站页面。
 * 只接受以单个斜杠开头、且不包含外部站点标记的地址。
 */
function isInternalRoute(value: string): boolean {
  return value.startsWith('/') && !value.startsWith('//') && !value.includes('://');
}

/**
 * 生成用于比较两条页面定义的文字。
 * 同一地址只有页面类型、模块和页面文件都一致时，才允许被多个菜单共用。
 */
function routeSignature(route: RouteRecordRaw): string {
  const meta = route.meta ?? {};
  return `${route.path}|${meta.entryType}|${meta.moduleAlias}|${meta.componentPath ?? ''}`;
}

// Compile original diagnostics independently of any user's selected language.
const declarationLanguage = createLanguageContext({ languageTag: 'zh-CN' });
function declaredRouteText(
  key: string,
  original: string,
  args?: Record<string, unknown>,
): OperationErrorMessage {
  return codeOperationErrorMessage(declarationLanguage, key, original, args);
}
