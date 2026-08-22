import type { MenuClient, SessionClient } from '@muyun/web-core';
import type {
  MenuNavigationTarget,
  MenuRecord,
  MenuTab,
  MenuTreeNode,
  PageDescriptor,
  WorkbenchStartupState,
} from '@muyun/web-contracts';
import {
  createMenuTab,
  findFirstNavigationMenu,
  getMenuNavigationTarget,
  isTabMenuTarget,
  pageDescriptorToUrl,
  resolvePageDescriptor,
  tabIdentityKeyOf,
  tabKeyOf,
  tryPageDescriptorFromUrl,
  withPageInstanceKey,
  type PageDescriptorResolveOptions,
} from '@muyun/platform-workbench';
import {
  createWorkspaceViewDescriptor,
  resolveWorkspaceView,
} from '../platform-admin-runtime/workspaceViews';
import {
  createOpenApiCatalogPageDescriptor,
  createModuleOpenApiPageDescriptor,
  isOpenApiCatalogPath,
  isModuleOpenApiPage,
  moduleAliasFromOpenApiPath,
} from '../platform-admin-runtime/moduleOpenApi';

export interface WorkbenchStartupClients {
  sessionClient: SessionClient;
  menuClient: MenuClient;
}

export async function loadWorkbenchStartupState(
  clients: WorkbenchStartupClients,
  options: PageDescriptorResolveOptions = {},
): Promise<WorkbenchStartupState> {
  const [currentUser, menuResponse, tenantBranding] = await Promise.all([
    clients.sessionClient.current(),
    clients.menuClient.mine(),
    loadTenantBranding(clients.sessionClient),
  ]);
  const initialTab = initialTabOf(menuResponse.records, options);

  return {
    session: { currentUser, ...(tenantBranding ? { tenantBranding } : {}) },
    menus: menuResponse.records,
    tabs: initialTab ? [initialTab] : [],
    activeTabKey: initialTab?.key,
  };
}

/** Branding is decorative; a transient failure must not prevent an authenticated workbench from starting. */
async function loadTenantBranding(sessionClient: SessionClient) {
  try {
    return await sessionClient.tenantBranding?.();
  } catch {
    return undefined;
  }
}

export function openMenuTab(
  tabs: MenuTab[],
  menu: MenuRecord,
  target: MenuNavigationTarget,
  options: PageDescriptorResolveOptions = {},
): { tabs: MenuTab[]; activeTabKey: string } {
  const tab = createMenuTab(menu, target, options);
  const existing = tabs.find((item) => item.key === tab.key);
  if (existing) {
    // A shared URL may have opened this key before its menu route was ready.
    // Once the user selects the menu, let the authoritative menu entry restore
    // its title and descriptor while retaining that page instance's URL state.
    const refreshed = existing.pageDescriptor
      ? createRestoredMenuTab(menu, target, existing.pageDescriptor, options)
      : tab;
    return {
      tabs: tabs.map((item) => (item.key === tab.key ? refreshed : item)),
      activeTabKey: tab.key,
    };
  }

  return { tabs: [...tabs, tab], activeTabKey: tab.key };
}

export function openDirectTab(
  tabs: MenuTab[],
  descriptor: PageDescriptor,
  options: PageDescriptorResolveOptions = {},
): { tabs: MenuTab[]; activeTabKey: string; created: boolean } {
  const tab = createDirectTab(withPageInstanceKey(descriptor), options);
  const existing = tabs.find((item) => {
    if (item.key === tab.key) return true;
    return (
      tab.pageDescriptor?.tabPolicy.identity === 'by-params' &&
      item.pageDescriptor?.tabPolicy.identity === 'by-params' &&
      tabIdentityKeyOf(item.pageDescriptor) === tabIdentityKeyOf(tab.pageDescriptor)
    );
  });
  if (existing) return { tabs, activeTabKey: existing.key, created: false };
  return { tabs: [...tabs, tab], activeTabKey: tab.key, created: true };
}

export function menuTargetUrl(
  menu: MenuRecord,
  target: MenuNavigationTarget,
  options: PageDescriptorResolveOptions = {},
): string {
  if (target.menuType === 'link' && target.openMode === 'window') {
    return target.externalUrl;
  }
  return pageDescriptorToUrl(resolvePageDescriptor(target, { ...options, title: menu.title }), options);
}

export function activeTabUrlOf(state: WorkbenchStartupState): string | undefined {
  const activeTab = (state.tabs ?? []).find((tab) => tab.key === state.activeTabKey);
  if (activeTab?.fullPath) {
    return activeTab.fullPath;
  }
  const descriptor =
    activeTab?.pageDescriptor ??
    (activeTab?.target ? resolvePageDescriptor(activeTab.target, { title: activeTab.title }) : undefined);
  if (
    descriptor?.pageType === 'platform-route' &&
    isModuleOpenApiPage(descriptor) &&
    descriptor.target.route
  ) {
    return descriptor.target.route;
  }
  return descriptor ? pageDescriptorToUrl(descriptor) : undefined;
}

export function restoreWorkbenchStartupStateFromUrl(
  state: WorkbenchStartupState,
  url: string,
  options: PageDescriptorResolveOptions = {},
  newInstance = false,
): WorkbenchStartupState {
  if (url === '/' || url === '') {
    return state;
  }

  const pathname = url.split(/[?#]/, 1)[0] ?? '';
  const openApiModuleAlias = moduleAliasFromOpenApiPath(pathname);
  const descriptor = openApiModuleAlias
    ? createModuleOpenApiPageDescriptor(openApiModuleAlias)
    : isOpenApiCatalogPath(pathname)
      ? createOpenApiCatalogPageDescriptor()
      : tryPageDescriptorFromUrl(url, options);
  if (!descriptor) {
    return state;
  }

  const explicitMenu = descriptor.menuId ? findMenuById(state.menus, descriptor.menuId) : undefined;
  const explicitTarget = explicitMenu ? getMenuNavigationTarget(explicitMenu) : undefined;
  const descriptorWithExplicitMenuTitle =
    explicitMenu && explicitTarget && explicitMenuOwnsDynamicDescriptor(explicitTarget, descriptor)
      ? { ...descriptor, title: explicitMenu.title }
      : descriptor;
  const workspaceDescriptor = restoredWorkspaceViewDescriptor(descriptorWithExplicitMenuTitle);
  const effectiveDescriptor = workspaceDescriptor ?? descriptorWithExplicitMenuTitle;
  const descriptorForOpen = newInstance ? withPageInstanceKey(effectiveDescriptor) : effectiveDescriptor;
  const menu =
    explicitMenu && menuMatchesDescriptor(explicitMenu, descriptorForOpen, options)
      ? explicitMenu
      : findMenuByDescriptor(state.menus, descriptorForOpen, options);
  const target = menu === explicitMenu ? explicitTarget : menu ? getMenuNavigationTarget(menu) : undefined;
  const tab = workspaceDescriptor
    ? createDirectTab(workspaceDescriptor, options)
    : menu && target && isTabMenuTarget(target)
      ? createRestoredMenuTab(menu, target, descriptorForOpen, options)
      : createDirectTab(descriptorForOpen, options);
  const existingTabs = state.tabs ?? [];
  const tabs = upsertTab(existingTabs, tab);

  return {
    ...state,
    tabs,
    activeTabKey: tab.key,
  };
}

function restoredWorkspaceViewDescriptor(descriptor: PageDescriptor) {
  if (descriptor.pageType !== 'business-route') return undefined;
  const workspaceView = resolveWorkspaceView(descriptor);
  return workspaceView
    ? createWorkspaceViewDescriptor(
        workspaceView.view,
        workspaceView.input,
        workspaceView.presentation,
        descriptor.title,
      )
    : undefined;
}

/** A menu-id URL may arrive before its dedicated Vue route is registered. */
function explicitMenuOwnsDynamicDescriptor(
  target: MenuNavigationTarget,
  descriptor: PageDescriptor,
): boolean {
  return (
    descriptor.pageType === 'dynamic-module' &&
    target.menuType === 'module' &&
    target.moduleAlias === descriptor.target.moduleAlias
  );
}

export function closeMenuTab(
  tabs: MenuTab[],
  activeTabKey: string | undefined,
  closedTabKey: string,
): { tabs: MenuTab[]; activeTabKey: string | undefined } {
  const closedIndex = tabs.findIndex((tab) => tab.key === closedTabKey);
  if (closedIndex < 0) {
    return { tabs, activeTabKey };
  }

  const nextTabs = tabs.filter((tab) => tab.key !== closedTabKey);
  if (activeTabKey !== closedTabKey) {
    return { tabs: nextTabs, activeTabKey };
  }

  return {
    tabs: nextTabs,
    activeTabKey: nextTabs[closedIndex]?.key ?? nextTabs[closedIndex - 1]?.key,
  };
}

/** Closes the selected closable tabs and chooses the nearest remaining tab when the active one closes. */
export function closeMenuTabs(
  tabs: MenuTab[],
  activeTabKey: string | undefined,
  closedTabKeys: string[],
): { tabs: MenuTab[]; activeTabKey: string | undefined } {
  const closedKeys = new Set(closedTabKeys);
  const activeIndex = tabs.findIndex((tab) => tab.key === activeTabKey);
  const nextTabs = tabs.filter((tab) => !closedKeys.has(tab.key) || tab.closable === false);
  if (!activeTabKey || !closedKeys.has(activeTabKey)) {
    return { tabs: nextTabs, activeTabKey };
  }
  const nextActive = nextTabs.find((tab) => tabs.indexOf(tab) > activeIndex) ?? nextTabs.at(-1);
  return { tabs: nextTabs, activeTabKey: nextActive?.key };
}

/** Keeps account-pinned tabs at the left while ordinary tab order remains a browser-session concern. */
export function arrangeLockedMenuTabs(
  tabs: MenuTab[],
  lockedTabs: MenuTab[],
  includeMissingLockedTabs = true,
): MenuTab[] {
  const currentTabs = new Map(tabs.map((tab) => [tab.key, tab]));
  const lockedKeys = new Set<string>();
  const pinned = lockedTabs.flatMap((lockedTab) => {
    if (lockedKeys.has(lockedTab.key)) return [];
    lockedKeys.add(lockedTab.key);
    const currentTab = currentTabs.get(lockedTab.key);
    return currentTab
      ? [refreshCurrentTabFromLockedMenu(currentTab, lockedTab)]
      : includeMissingLockedTabs
        ? [lockedTab]
        : [];
  });
  return [...pinned, ...tabs.filter((tab) => !lockedKeys.has(tab.key))];
}

/**
 * Locked menu tabs are rebuilt from the current account's visible menus.  When
 * a shared URL opens the same tab key, keep its page instance and parameters,
 * but never let its fallback title replace the rebuilt menu facts.
 */
function refreshCurrentTabFromLockedMenu(currentTab: MenuTab, lockedTab: MenuTab): MenuTab {
  const currentDescriptor = currentTab.pageDescriptor;
  const lockedDescriptor = lockedTab.pageDescriptor;
  if (!currentDescriptor || !lockedDescriptor || !lockedTab.target) {
    return currentTab;
  }

  const descriptor: PageDescriptor = {
    ...currentDescriptor,
    title: lockedTab.title,
    menuId: lockedTab.target.menuId,
    tabPolicy: lockedDescriptor.tabPolicy,
  };
  return {
    ...lockedTab,
    ...createDirectTab(descriptor),
    target: lockedTab.target,
  };
}

/**
 * Restores account-locked tabs from two authority-checked sources only:
 * currently visible tab menus and registered, URL-restorable workspace views.
 * Arbitrary stored routes are deliberately not revived.
 */
export function restoreLockedWorkbenchTabs(
  lockedTabs: MenuTab[],
  menus: MenuTreeNode[],
  options: PageDescriptorResolveOptions = {},
): MenuTab[] {
  const availableMenus = new Map<string, { menu: MenuRecord; target: MenuNavigationTarget }>();
  collectTabMenus(menus, availableMenus);
  const restoredKeys = new Set<string>();
  return lockedTabs.flatMap((tab) => {
    const menuId = tab.pageDescriptor?.menuId ?? tab.target?.menuId ?? menuIdFromTabKey(tab.key);
    if (menuId) {
      if (restoredKeys.has(menuId)) return [];
      const available = availableMenus.get(menuId);
      if (!available) return [];
      restoredKeys.add(menuId);
      return [createMenuTab(available.menu, available.target, options)];
    }

    const workspaceTab = restoreLockedWorkspaceTab(tab);
    if (!workspaceTab || restoredKeys.has(workspaceTab.key)) return [];
    restoredKeys.add(workspaceTab.key);
    return [workspaceTab];
  });
}

function menuIdFromTabKey(key: string): string | undefined {
  return key.startsWith('menu:') ? key.slice('menu:'.length) || undefined : undefined;
}

function restoreLockedWorkspaceTab(tab: MenuTab): MenuTab | undefined {
  const descriptor = tab.pageDescriptor;
  if (descriptor?.pageType !== 'business-route') return undefined;
  const workspaceView = resolveWorkspaceView(descriptor);
  if (!workspaceView || workspaceView.presentation !== 'tab') return undefined;
  // Recreate title, URL and identity from the registered definition instead
  // of trusting a stale client-side snapshot.
  return createDirectTab(createWorkspaceViewDescriptor(workspaceView.view, workspaceView.input, 'tab'));
}

export function updateLockedMenuTabs(lockedTabs: MenuTab[], tab: MenuTab): MenuTab[] {
  const existing = lockedTabs.findIndex((item) => item.key === tab.key);
  if (existing < 0) return [...lockedTabs, tab];
  return lockedTabs.map((item, index) => (index === existing ? tab : item));
}

function collectTabMenus(
  nodes: MenuTreeNode[],
  targetMenus: Map<string, { menu: MenuRecord; target: MenuNavigationTarget }>,
) {
  for (const node of nodes) {
    const target = getMenuNavigationTarget(node.record);
    if (target && isTabMenuTarget(target)) targetMenus.set(node.record.id, { menu: node.record, target });
    collectTabMenus(node.children, targetMenus);
  }
}

export function removeLockedMenuTabs(lockedTabs: MenuTab[], keys: string[]): MenuTab[] {
  const removed = new Set(keys);
  return lockedTabs.filter((tab) => !removed.has(tab.key));
}

/** Reorders the open tabs for the current browser session without affecting URL restoration. */
export function reorderMenuTabs(tabs: MenuTab[], keys: string[], lockedTabKeys: string[] = []): MenuTab[] {
  if (tabs.length !== keys.length || new Set(keys).size !== tabs.length) return tabs;
  const tabsByKey = new Map(tabs.map((tab) => [tab.key, tab]));
  if (keys.some((key) => !tabsByKey.has(key))) return tabs;
  const orderedTabs = keys.map((key) => tabsByKey.get(key)!);
  const lockedKeys = new Set(lockedTabKeys);
  return [
    ...orderedTabs.filter((tab) => lockedKeys.has(tab.key)),
    ...orderedTabs.filter((tab) => !lockedKeys.has(tab.key)),
  ];
}

function initialTabOf(menus: WorkbenchStartupState['menus'], options: PageDescriptorResolveOptions) {
  const menu = findFirstNavigationMenu(menus);
  const target = menu ? getMenuNavigationTarget(menu) : undefined;
  return menu && target ? createMenuTab(menu, target, options) : undefined;
}

function createDirectTab(descriptor: PageDescriptor, options: PageDescriptorResolveOptions = {}): MenuTab {
  const fullPath = pageDescriptorToUrl(descriptor, options);
  return {
    ...(pageInstanceKeyOfDescriptor(descriptor)
      ? { instanceKey: pageInstanceKeyOfDescriptor(descriptor) }
      : {}),
    key: tabKeyOf(descriptor),
    title: descriptor.title ?? directTabTitleOf(descriptor),
    fullPath,
    pageDescriptor: descriptor,
    restoreState: { url: fullPath },
    closable: true,
  };
}

function pageInstanceKeyOfDescriptor(descriptor: PageDescriptor): string | undefined {
  const query =
    descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route'
      ? (descriptor.target.query ?? descriptor.params)
      : descriptor.params;
  const value = query?.InstanceKey;
  const first = Array.isArray(value) ? value[0] : value;
  return typeof first === 'string' && first ? first : undefined;
}

function upsertTab(tabs: MenuTab[], tab: MenuTab): MenuTab[] {
  const index = tabs.findIndex((item) => item.key === tab.key);
  if (index < 0) {
    return [...tabs, tab];
  }

  return tabs.map((item, itemIndex) => (itemIndex === index ? tab : item));
}

function createRestoredMenuTab(
  menu: MenuRecord,
  target: MenuNavigationTarget,
  descriptor: PageDescriptor,
  options: PageDescriptorResolveOptions,
): MenuTab {
  const tab = createMenuTab(menu, target, options);
  const resolvedDescriptor = tab.pageDescriptor ?? descriptor;
  const pageDescriptor: PageDescriptor =
    isRouteDescriptor(descriptor) && isRouteDescriptor(resolvedDescriptor)
      ? {
          ...descriptor,
          title: descriptor.title ?? menu.title,
          menuId: menu.id,
          target: {
            ...descriptor.target,
            moduleAlias: descriptor.target.moduleAlias ?? resolvedDescriptor.target.moduleAlias,
          },
          tabPolicy: resolvedDescriptor.tabPolicy,
        }
      : isUrlDescriptor(descriptor) && isUrlDescriptor(resolvedDescriptor)
        ? {
            ...descriptor,
            title: descriptor.title ?? menu.title,
            menuId: menu.id,
            target: {
              ...descriptor.target,
              moduleAlias: descriptor.target.moduleAlias ?? resolvedDescriptor.target.moduleAlias,
            },
            tabPolicy: resolvedDescriptor.tabPolicy,
          }
        : {
            ...descriptor,
            title: descriptor.title ?? menu.title,
            menuId: menu.id,
            tabPolicy: resolvedDescriptor.tabPolicy,
          };

  return {
    ...tab,
    ...createDirectTab(pageDescriptor, options),
    target,
  };
}

function isRouteDescriptor(
  descriptor: PageDescriptor,
): descriptor is Extract<PageDescriptor, { pageType: 'platform-route' | 'business-route' }> {
  return descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route';
}

function isUrlDescriptor(
  descriptor: PageDescriptor,
): descriptor is Extract<PageDescriptor, { pageType: 'remote-url' | 'external-link' }> {
  return descriptor.pageType === 'remote-url' || descriptor.pageType === 'external-link';
}

function directTabTitleOf(descriptor: PageDescriptor): string {
  if (descriptor.pageType === 'dynamic-module') {
    return descriptor.target.moduleAlias;
  }

  if (descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route') {
    return descriptor.target.route ?? descriptor.target.routeName ?? descriptor.target.pageKey ?? 'workspace';
  }

  return descriptor.target.url;
}

function findMenuByDescriptor(
  nodes: WorkbenchStartupState['menus'],
  descriptor: PageDescriptor,
  options: PageDescriptorResolveOptions,
): MenuRecord | undefined {
  for (const node of nodes) {
    if (menuMatchesDescriptor(node.record, descriptor, options)) {
      return node.record;
    }

    const childMenu = findMenuByDescriptor(node.children, descriptor, options);
    if (childMenu) {
      return childMenu;
    }
  }

  return undefined;
}

function menuMatchesDescriptor(
  menu: MenuRecord,
  descriptor: PageDescriptor,
  options: PageDescriptorResolveOptions,
): boolean {
  const target = getMenuNavigationTarget(menu);
  const menuDescriptor = target
    ? resolvePageDescriptor(target, { ...options, title: menu.title })
    : undefined;
  return menuDescriptor ? matchesPageDescriptor(menuDescriptor, descriptor) : false;
}

function matchesPageDescriptor(left: PageDescriptor, right: PageDescriptor): boolean {
  if (left.pageType !== right.pageType || left.hostType !== right.hostType) {
    return false;
  }

  if (left.pageType === 'dynamic-module' && right.pageType === 'dynamic-module') {
    return (
      left.target.moduleAlias === right.target.moduleAlias &&
      (left.target.pageMode ?? 'LIST') === (right.target.pageMode ?? 'LIST') &&
      (!right.target.defaultUiConfigId || left.target.defaultUiConfigId === right.target.defaultUiConfigId) &&
      (!right.target.defaultQueryTemplateId ||
        left.target.defaultQueryTemplateId === right.target.defaultQueryTemplateId)
    );
  }

  if (
    (left.pageType === 'platform-route' || left.pageType === 'business-route') &&
    (right.pageType === 'platform-route' || right.pageType === 'business-route')
  ) {
    return (
      left.target.route === right.target.route &&
      left.target.routeName === right.target.routeName &&
      left.target.pageKey === right.target.pageKey
    );
  }

  if (
    (left.pageType === 'remote-url' || left.pageType === 'external-link') &&
    (right.pageType === 'remote-url' || right.pageType === 'external-link')
  ) {
    return left.target.url === right.target.url;
  }

  return false;
}

function findMenuById(nodes: WorkbenchStartupState['menus'], menuId: string): MenuRecord | undefined {
  for (const node of nodes) {
    if (node.record.id === menuId) {
      return node.record;
    }

    const childMenu = findMenuById(node.children, menuId);
    if (childMenu) {
      return childMenu;
    }
  }

  return undefined;
}
