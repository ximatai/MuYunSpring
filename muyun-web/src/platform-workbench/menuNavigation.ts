import type {
  MenuNavigationTarget,
  MenuRecord,
  MenuPageMode,
  MenuTab,
  MenuTreeNode,
  PageDescriptor,
  PageLayoutMode,
  RoutePageTarget,
  RouteQueryPrimitive,
  RouteQueryValue,
} from '@muyun/web-contracts';

export interface PageDescriptorResolveOptions {
  title?: string;
  platformRoutePrefixes?: string[];
  businessRoutePrefixes?: string[];
  /** Optional legacy mapping for framework-owned static business pages. */
  businessModuleRoutes?: Record<string, string>;
  /** Stable readable routes whose content is rendered by the standard dynamic module host. */
  dynamicModuleRoutes?: Record<string, string>;
  /** Page layout contracts keyed by a static business route. */
  businessRouteLayouts?: Record<string, PageLayoutMode>;
  businessRouteNames?: string[];
  businessPageKeys?: string[];
}

export interface PageDescriptorUrlParseOptions {
  title?: string;
  platformRoutePrefixes?: string[];
  businessRoutePrefixes?: string[];
  businessRouteLayouts?: Record<string, PageLayoutMode>;
  dynamicModuleRoutes?: Record<string, string>;
}

// Workspace views are URL-restorable business pages even when an application
// has not reserved a domain route prefix for their generated fallback route.
const defaultBusinessRoutePrefixes: string[] = ['/_workspace'];
const WORKBENCH_MENU_ID_QUERY_KEY = '_muyunMenuId';
const WORKBENCH_TITLE_QUERY_KEY = '_muyunTitle';
/** Accepted only to keep old shared URLs harmless; it never becomes page state. */
const LEGACY_WORKBENCH_TITLE_QUERY_KEY = '_muyunTitle';
const WORKBENCH_ENTRY_PARAMS_QUERY_KEY = '_muyunEntryParams';
const legacyWorkbenchQueryKeys = ['entryParamsJson', 'menuId', 'title'];

export function getMenuNavigationTarget(menu: MenuRecord): MenuNavigationTarget | undefined {
  if (menu.enabled === false) {
    return undefined;
  }

  if (!menu.openMode || !menu.moduleAlias || !menu.entryType) {
    return undefined;
  }

  if (menu.entryType === 'link' && menu.externalUrl) {
    return {
      menuId: menu.id,
      menuType: 'link',
      openMode: menu.openMode,
      externalUrl: menu.externalUrl,
      moduleAlias: menu.moduleAlias,
      entryParamsJson: menu.entryParamsJson,
    };
  }

  if (menu.entryType === 'route' && menu.route) {
    return {
      menuId: menu.id,
      menuType: 'route',
      openMode: menu.openMode,
      route: menu.route,
      moduleAlias: menu.moduleAlias,
      entryParamsJson: menu.entryParamsJson,
    };
  }

  if (menu.entryType !== 'module' || menu.route || menu.externalUrl) {
    return undefined;
  }

  return {
    menuId: menu.id,
    menuType: 'module',
    openMode: menu.openMode,
    moduleAlias: menu.moduleAlias,
    pageMode: menu.pageMode,
    defaultUiConfigId: menu.defaultUiConfigId,
    defaultQueryTemplateId: menu.defaultQueryTemplateId,
    entryParamsJson: menu.entryParamsJson,
  };
}

export function isTabMenuTarget(target: MenuNavigationTarget): boolean {
  return target.openMode === 'tab';
}

export function isWindowMenuTarget(target: MenuNavigationTarget): boolean {
  return target.openMode === 'window';
}

export function resolvePageDescriptor(
  target: MenuNavigationTarget,
  options: PageDescriptorResolveOptions = {},
): PageDescriptor {
  if (target.menuType === 'module') {
    const businessRoute = options.businessModuleRoutes?.[target.moduleAlias];
    if (businessRoute) {
      return {
        pageType: 'business-route',
        openMode: 'workbench-route',
        hostType: 'business-route-host',
        title: options.title,
        layout: businessRouteLayoutsOf({ route: businessRoute }, options),
        menuId: target.menuId,
        target: { route: businessRoute, moduleAlias: target.moduleAlias },
        entryParamsJson: target.entryParamsJson,
        tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
      };
    }
    return {
      pageType: 'dynamic-module',
      openMode: 'dynamic-runner',
      hostType: 'dynamic-module-host',
      title: options.title,
      menuId: target.menuId,
      target: {
        moduleAlias: target.moduleAlias,
        pageMode: target.pageMode,
        defaultUiConfigId: target.defaultUiConfigId,
        defaultQueryTemplateId: target.defaultQueryTemplateId,
      },
      params: target.query,
      entryParamsJson: target.entryParamsJson,
      tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
    };
  }

  if (target.menuType === 'route') {
    const dynamicModuleAlias = options.dynamicModuleRoutes?.[target.route];
    if (dynamicModuleAlias) {
      return {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'dynamic-module-host',
        title: options.title,
        menuId: target.menuId,
        target: { moduleAlias: dynamicModuleAlias, pageMode: 'LIST' },
        params: target.query,
        entryParamsJson: target.entryParamsJson,
        tabPolicy: {
          identity: target.menuId ? ('by-menu' as const) : ('by-target' as const),
          closable: true,
          cacheable: true,
        },
      };
    }
    const routeTarget = routeTargetOf(target.route, target.query, target.moduleAlias);
    const pageType = isBusinessRouteTarget(routeTarget, options) ? 'business-route' : 'platform-route';
    const descriptorBase = {
      openMode: 'workbench-route' as const,
      title: options.title,
      menuId: target.menuId,
      target: routeTarget,
      params: target.query,
      entryParamsJson: target.entryParamsJson,
      tabPolicy: {
        identity: target.menuId ? ('by-menu' as const) : ('by-target' as const),
        closable: true,
        cacheable: true,
      },
    };

    return pageType === 'business-route'
      ? {
          ...descriptorBase,
          pageType,
          hostType: 'business-route-host',
          layout: businessRouteLayoutsOf(routeTarget, options),
        }
      : { ...descriptorBase, pageType, hostType: 'platform-route-host' };
  }

  if (target.openMode === 'tab') {
    return {
      pageType: 'remote-url',
      openMode: 'iframe',
      hostType: 'external-page-host',
      title: options.title,
      menuId: target.menuId,
      target: { url: target.externalUrl, moduleAlias: target.moduleAlias },
      entryParamsJson: target.entryParamsJson,
      tabPolicy: {
        identity: target.menuId ? 'by-menu' : 'by-target',
        closable: true,
      },
    };
  }

  return {
    pageType: 'external-link',
    openMode: 'new-window',
    hostType: 'external-page-host',
    title: options.title,
    menuId: target.menuId,
    target: { url: target.externalUrl, moduleAlias: target.moduleAlias },
    entryParamsJson: target.entryParamsJson,
    tabPolicy: {
      identity: target.menuId ? 'by-menu' : 'by-target',
      closable: true,
    },
  };
}

export function createMenuTab(
  menu: MenuRecord,
  target: MenuNavigationTarget,
  options: PageDescriptorResolveOptions = {},
): MenuTab {
  if (!isTabMenuTarget(target)) {
    throw new Error(`WINDOW menu target cannot be opened as a workbench tab: ${target.menuId}`);
  }

  const pageDescriptor = withPageInstanceKey(
    resolvePageDescriptor(target, { ...options, title: options.title ?? menu.title }),
  );
  const instanceKey = pageInstanceKeyOf(pageDescriptor)!;
  return {
    instanceKey,
    key: tabKeyOf(pageDescriptor),
    title: menu.title,
    fullPath: pageDescriptorToUrl(pageDescriptor),
    target,
    pageDescriptor,
    restoreState: pageDescriptor.restoreState,
    closable: true,
  };
}

/** Adds a page-instance key once and preserves it on address replacement or restoration. */
export function withPageInstanceKey(
  descriptor: PageDescriptor,
  instanceKey = crypto.randomUUID(),
): PageDescriptor {
  if (pageInstanceKeyOf(descriptor)) return descriptor;
  if (descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route') {
    const query = { ...(descriptor.target.query ?? descriptor.params), InstanceKey: instanceKey };
    return { ...descriptor, target: { ...descriptor.target, query }, params: query };
  }
  return { ...descriptor, params: { ...descriptor.params, InstanceKey: instanceKey } };
}

export function findFirstNavigationMenu(nodes: MenuTreeNode[]): MenuRecord | undefined {
  for (const node of nodes) {
    const target = getMenuNavigationTarget(node.record);
    if (target && isTabMenuTarget(target)) {
      return node.record;
    }

    const childMenu: MenuRecord | undefined = findFirstNavigationMenu(node.children);
    if (childMenu) {
      return childMenu;
    }
  }

  return undefined;
}

export function tabKeyOf(descriptor: PageDescriptor): string {
  const baseKey = tabIdentityKeyOf(descriptor);
  if (descriptor.tabPolicy.identity === 'by-menu' && descriptor.menuId) return baseKey;
  const instanceKey = pageInstanceKeyOf(descriptor);
  return instanceKey ? `${baseKey}:InstanceKey:${instanceKey}` : baseKey;
}

/**
 * Stable page intent without the workbench instance marker.
 * `by-params` workspace views use this to focus an existing logical view while
 * the actual tab key still contains InstanceKey for cached route isolation.
 */
export function tabIdentityKeyOf(descriptor: PageDescriptor): string {
  if (descriptor.tabPolicy.identity === 'by-menu' && descriptor.menuId) {
    return `menu:${descriptor.menuId}`;
  }

  if (descriptor.tabPolicy.identity === 'by-params' && descriptor.params) {
    const baseKey = descriptor.menuId
      ? `menu:${descriptor.menuId}`
      : `${descriptor.pageType}:${stableTargetKeyOf(descriptor)}`;
    const identityParams = Object.fromEntries(
      Object.entries(descriptor.params).filter(([key]) => key !== 'InstanceKey'),
    );
    return `${baseKey}:${stableQueryString(identityParams)}`;
  }

  return `${descriptor.pageType}:${stableTargetKeyOf(descriptor)}`;
}

export function pageInstanceKeyOf(descriptor: PageDescriptor): string | undefined {
  const query =
    descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route'
      ? (descriptor.target.query ?? descriptor.params)
      : descriptor.params;
  const value = query?.InstanceKey;
  return Array.isArray(value) ? stringValue(value[0]) : stringValue(value);
}

export function pageDescriptorToUrl(descriptor: PageDescriptor): string {
  if (descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route') {
    if (descriptor.target.route) {
      return appendQuery(descriptor.target.route, {
        ...(descriptor.target.query ?? descriptor.params),
        [WORKBENCH_ENTRY_PARAMS_QUERY_KEY]: descriptor.entryParamsJson,
        [WORKBENCH_MENU_ID_QUERY_KEY]: descriptor.menuId,
      });
    }

    const entryQuery = descriptor.target.query ?? descriptor.params ?? {};
    const query: Record<string, RouteQueryValue> = {
      ...entryQuery,
      pageType: descriptor.pageType,
      routeName: descriptor.target.routeName,
      pageKey: descriptor.target.pageKey,
      [WORKBENCH_MENU_ID_QUERY_KEY]: descriptor.menuId,
    };
    return appendQuery('/platform/workspace', query);
  }

  if (descriptor.pageType === 'dynamic-module') {
    const pageMode = descriptor.target.pageMode?.toLowerCase() ?? 'list';
    return appendQuery(`/platform/dynamic/${descriptor.target.moduleAlias}/${pageMode}`, {
      ...descriptor.params,
      [WORKBENCH_ENTRY_PARAMS_QUERY_KEY]: descriptor.entryParamsJson,
      uiConfigId: descriptor.target.defaultUiConfigId,
      queryTemplateId: descriptor.target.defaultQueryTemplateId,
      [WORKBENCH_MENU_ID_QUERY_KEY]: descriptor.menuId,
    });
  }

  if (descriptor.pageType === 'remote-url' || descriptor.pageType === 'external-link') {
    return appendQuery('/platform/external', {
      ...descriptor.params,
      url: descriptor.target.url,
      mode: descriptor.openMode,
      [WORKBENCH_MENU_ID_QUERY_KEY]: descriptor.menuId,
    });
  }

  return exhaustivePageDescriptor(descriptor);
}

export function pageDescriptorFromUrl(
  url: string,
  options: PageDescriptorUrlParseOptions = {},
): PageDescriptor {
  const parsedUrl = parseUrl(url);
  const path = parsedUrl.pathname;
  const query = queryRecordOf(parsedUrl.searchParams);

  const dynamicModuleAlias = options.dynamicModuleRoutes?.[path];
  if (dynamicModuleAlias) {
    const menuId = workbenchQueryValue(query, WORKBENCH_MENU_ID_QUERY_KEY, 'menuId');
    return {
      pageType: 'dynamic-module',
      openMode: 'dynamic-runner',
      hostType: 'dynamic-module-host',
      title: workbenchQueryValue(query, WORKBENCH_TITLE_QUERY_KEY, 'title') ?? options.title,
      menuId,
      target: { moduleAlias: dynamicModuleAlias, pageMode: 'LIST' },
      params: withoutKeys(query, [
        ...legacyWorkbenchQueryKeys,
        WORKBENCH_MENU_ID_QUERY_KEY,
        WORKBENCH_TITLE_QUERY_KEY,
      ]),
      entryParamsJson: workbenchQueryValue(query, WORKBENCH_ENTRY_PARAMS_QUERY_KEY, 'entryParamsJson'),
      tabPolicy: menuId
        ? { identity: 'by-menu', closable: true, cacheable: true }
        : { identity: 'by-target', closable: true, cacheable: true },
    };
  }

  if (path === '/platform/dynamic') {
    throw new Error(`Invalid dynamic module URL: ${url}`);
  }

  if (path.startsWith('/platform/dynamic/')) {
    const [, , , moduleAlias, pageMode] = path.split('/');
    if (!moduleAlias) {
      throw new Error(`Invalid dynamic module URL: ${url}`);
    }
    const params = withoutKeys(query, [
      ...legacyWorkbenchQueryKeys,
      WORKBENCH_ENTRY_PARAMS_QUERY_KEY,
      WORKBENCH_MENU_ID_QUERY_KEY,
      LEGACY_WORKBENCH_TITLE_QUERY_KEY,
      'queryTemplateId',
      'uiConfigId',
    ]);
    const menuId = workbenchQueryValue(query, WORKBENCH_MENU_ID_QUERY_KEY, 'menuId');
    return {
      pageType: 'dynamic-module',
      openMode: 'dynamic-runner',
      hostType: 'dynamic-module-host',
      title: options.title,
      menuId,
      target: {
        moduleAlias: decodeURIComponent(moduleAlias ?? ''),
        pageMode: pageModeOf(pageMode),
        defaultUiConfigId: stringValue(query.uiConfigId),
        defaultQueryTemplateId: stringValue(query.queryTemplateId),
      },
      params,
      entryParamsJson: workbenchQueryValue(query, WORKBENCH_ENTRY_PARAMS_QUERY_KEY, 'entryParamsJson'),
      tabPolicy: menuId
        ? { identity: 'by-menu' as const, closable: true, cacheable: true }
        : { identity: 'by-target' as const, closable: true, cacheable: true },
    };
  }

  if (path === '/platform/external') {
    const remoteUrl = stringValue(query.url) ?? '';
    if (!remoteUrl) {
      throw new Error(`Invalid external page URL: ${url}`);
    }
    const openMode = stringValue(query.mode) === 'new-window' ? 'new-window' : 'iframe';
    const menuId = workbenchQueryValue(query, WORKBENCH_MENU_ID_QUERY_KEY, 'menuId');
    const tabPolicy = menuId
      ? { identity: 'by-menu' as const, closable: true }
      : { identity: 'by-target' as const, closable: true };
    const params = withoutKeys(query, [
      ...legacyWorkbenchQueryKeys,
      WORKBENCH_MENU_ID_QUERY_KEY,
      LEGACY_WORKBENCH_TITLE_QUERY_KEY,
      'url',
      'mode',
    ]);
    if (openMode === 'iframe') {
      return {
        pageType: 'remote-url',
        openMode,
        hostType: 'external-page-host',
        title: options.title,
        menuId,
        target: { url: remoteUrl },
        params,
        tabPolicy,
      };
    }

    return {
      pageType: 'external-link',
      openMode,
      hostType: 'external-page-host',
      title: options.title,
      menuId,
      target: { url: remoteUrl },
      params,
      tabPolicy,
    };
  }

  if (path === '/platform/workspace' && !query.routeName && !query.pageKey) {
    throw new Error(`Invalid workspace URL: ${url}`);
  }

  if (path === '/platform/workspace' && (query.routeName || query.pageKey)) {
    const routeTarget: RoutePageTarget = {
      routeName: stringValue(query.routeName),
      pageKey: stringValue(query.pageKey),
      query: withoutKeys(query, [
        ...legacyWorkbenchQueryKeys,
        WORKBENCH_MENU_ID_QUERY_KEY,
        LEGACY_WORKBENCH_TITLE_QUERY_KEY,
        'pageType',
        'routeName',
        'pageKey',
      ]),
    };
    const pageType = stringValue(query.pageType) === 'business-route' ? 'business-route' : 'platform-route';
    const menuId = workbenchQueryValue(query, WORKBENCH_MENU_ID_QUERY_KEY, 'menuId');
    const descriptorBase = {
      openMode: 'workbench-route' as const,
      title: options.title,
      menuId,
      target: routeTarget,
      params: routeTarget.query,
      tabPolicy: menuId
        ? { identity: 'by-menu' as const, closable: true, cacheable: true }
        : { identity: 'by-target' as const, closable: true, cacheable: true },
    };

    if (pageType === 'business-route') {
      return {
        ...descriptorBase,
        pageType: 'business-route',
        hostType: 'business-route-host',
        layout: businessRouteLayoutsOf(routeTarget, options),
      };
    }

    return {
      ...descriptorBase,
      pageType: 'platform-route',
      hostType: 'platform-route-host',
    };
  }

  const pageType = isBusinessRoutePath(path, options) ? 'business-route' : 'platform-route';
  const menuId = workbenchQueryValue(query, WORKBENCH_MENU_ID_QUERY_KEY);
  const routeQuery = withoutKeys(query, [
    WORKBENCH_ENTRY_PARAMS_QUERY_KEY,
    WORKBENCH_MENU_ID_QUERY_KEY,
    LEGACY_WORKBENCH_TITLE_QUERY_KEY,
  ]);
  const descriptorBase = {
    openMode: 'workbench-route' as const,
    title: options.title,
    menuId,
    target: {
      route: path,
      query: routeQuery,
    },
    params: routeQuery,
    entryParamsJson: workbenchQueryValue(query, WORKBENCH_ENTRY_PARAMS_QUERY_KEY),
    tabPolicy: menuId
      ? { identity: 'by-menu' as const, closable: true, cacheable: true }
      : { identity: 'by-target' as const, closable: true, cacheable: true },
  };

  if (pageType === 'business-route') {
    return {
      ...descriptorBase,
      pageType: 'business-route',
      hostType: 'business-route-host',
      layout: businessRouteLayoutsOf(descriptorBase.target, options),
    };
  }

  return {
    ...descriptorBase,
    pageType: 'platform-route',
    hostType: 'platform-route-host',
  };
}

export function tryPageDescriptorFromUrl(
  url: string,
  options: PageDescriptorUrlParseOptions = {},
): PageDescriptor | undefined {
  try {
    return pageDescriptorFromUrl(url, options);
  } catch {
    return undefined;
  }
}

function routeTargetOf(
  route: string,
  query?: Record<string, RouteQueryValue>,
  moduleAlias?: string,
): RoutePageTarget {
  if (route.startsWith('/')) {
    return { route, moduleAlias, query };
  }

  if (route.includes('.')) {
    return { routeName: route, moduleAlias, query };
  }

  return { pageKey: route, moduleAlias, query };
}

function isBusinessRouteTarget(target: RoutePageTarget, options: PageDescriptorResolveOptions): boolean {
  return (
    (target.route ? isBusinessRoutePath(target.route, options) : false) ||
    (target.routeName ? options.businessRouteNames?.includes(target.routeName) === true : false) ||
    (target.pageKey ? options.businessPageKeys?.includes(target.pageKey) === true : false)
  );
}

function businessRouteLayoutsOf(
  target: RoutePageTarget,
  options: Pick<PageDescriptorResolveOptions, 'businessRouteLayouts'>,
): PageLayoutMode | undefined {
  return target.route ? options.businessRouteLayouts?.[target.route] : undefined;
}

function isBusinessRoutePath(path: string, options: PageDescriptorUrlParseOptions): boolean {
  const businessPrefixes = options.businessRoutePrefixes ?? defaultBusinessRoutePrefixes;
  const businessPrefixLength = longestMatchingRoutePrefixLength(path, businessPrefixes);
  if (businessPrefixLength < 0) {
    return false;
  }

  const platformPrefixes = options.platformRoutePrefixes ?? defaultPlatformRoutePrefixes;
  const platformPrefixLength = longestMatchingRoutePrefixLength(path, platformPrefixes);
  return platformPrefixLength < 0 || businessPrefixLength > platformPrefixLength;
}

function stableTargetKeyOf(descriptor: PageDescriptor): string {
  if (descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route') {
    return descriptor.target.route ?? descriptor.target.routeName ?? descriptor.target.pageKey ?? 'route';
  }

  if (descriptor.pageType === 'dynamic-module') {
    return [descriptor.target.moduleAlias, descriptor.target.pageMode ?? 'LIST'].join(':');
  }

  return descriptor.target.url;
}

function appendQuery(path: string, query?: Record<string, RouteQueryValue>): string {
  const search = query ? stableQueryString(query) : '';
  if (!search) {
    return path;
  }

  return `${path}${path.includes('?') ? '&' : '?'}${search}`;
}

function stableQueryString(query: Record<string, RouteQueryValue>): string {
  const params = new URLSearchParams();
  for (const key of Object.keys(query).sort()) {
    const value = query[key];
    if (Array.isArray(value)) {
      for (const item of value) {
        appendParam(params, key, item);
      }
      continue;
    }

    appendParam(params, key, value);
  }

  return params.toString();
}

function appendParam(params: URLSearchParams, key: string, value: RouteQueryPrimitive) {
  if (value === null || value === undefined) {
    return;
  }

  params.append(key, String(value));
}

function matchesRoutePrefix(path: string, prefix: string): boolean {
  const normalizedPrefix = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix;
  return path === normalizedPrefix || path.startsWith(`${normalizedPrefix}/`);
}

function longestMatchingRoutePrefixLength(path: string, prefixes: string[]): number {
  let longest = -1;
  for (const prefix of prefixes) {
    const normalizedPrefix = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix;
    if (matchesRoutePrefix(path, normalizedPrefix)) {
      longest = Math.max(longest, normalizedPrefix.length);
    }
  }
  return longest;
}

function parseUrl(url: string): URL {
  return new URL(url, 'http://muyun.local');
}

function withoutKeys(
  query: Record<string, RouteQueryValue>,
  keys: string[],
): Record<string, RouteQueryValue> | undefined {
  const result: Record<string, RouteQueryValue> = {};
  for (const key of Object.keys(query)) {
    if (!keys.includes(key)) {
      result[key] = query[key];
    }
  }

  return Object.keys(result).length > 0 ? result : undefined;
}

function queryRecordOf(searchParams: URLSearchParams): Record<string, RouteQueryValue> {
  const result: Record<string, RouteQueryValue> = {};
  for (const key of searchParams.keys()) {
    const values = searchParams.getAll(key);
    result[key] = values.length > 1 ? values : values[0];
  }

  return result;
}

function stringValue(value: RouteQueryValue): string | undefined {
  if (Array.isArray(value)) {
    return value[0] === null || value[0] === undefined ? undefined : String(value[0]);
  }

  return value === null || value === undefined ? undefined : String(value);
}

function workbenchQueryValue(
  query: Record<string, RouteQueryValue>,
  key: string,
  legacyKey?: string,
): string | undefined {
  return stringValue(query[key]) ?? (legacyKey ? stringValue(query[legacyKey]) : undefined);
}

const defaultPlatformRoutePrefixes = ['/platform'];

function pageModeOf(value: string | undefined): MenuPageMode | undefined {
  const normalized = value?.toUpperCase();
  return normalized === 'LIST' || normalized === 'FORM' || normalized === 'DETAIL' ? normalized : undefined;
}

function exhaustivePageDescriptor(descriptor: never): never {
  throw new Error(`Unsupported page descriptor: ${JSON.stringify(descriptor)}`);
}
