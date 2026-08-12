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
  tabKeyOf,
  tryPageDescriptorFromUrl,
  type PageDescriptorResolveOptions,
} from '@muyun/platform-workbench';
import {
  createWorkspaceViewDescriptor,
  resolveWorkspaceView,
} from '../platform-admin-runtime/workspaceViews';
import {
  createModuleOpenApiPageDescriptor,
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
  if (tabs.some((item) => item.key === tab.key)) {
    return { tabs, activeTabKey: tab.key };
  }

  return { tabs: [...tabs, tab], activeTabKey: tab.key };
}

export function openDirectTab(
  tabs: MenuTab[],
  descriptor: PageDescriptor,
): { tabs: MenuTab[]; activeTabKey: string; created: boolean } {
  const tab = createDirectTab(descriptor);
  const created = !tabs.some((item) => item.key === tab.key);
  return { tabs: upsertTab(tabs, tab), activeTabKey: tab.key, created };
}

export function menuTargetUrl(menu: MenuRecord, target: MenuNavigationTarget): string {
  return pageDescriptorToUrl(resolvePageDescriptor(target, { title: menu.title }));
}

export function activeTabUrlOf(state: WorkbenchStartupState): string | undefined {
  const activeTab = (state.tabs ?? []).find((tab) => tab.key === state.activeTabKey);
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
): WorkbenchStartupState {
  if (url === '/' || url === '') {
    return state;
  }

  const openApiModuleAlias = moduleAliasFromOpenApiPath(url.split(/[?#]/, 1)[0] ?? '');
  const descriptor = openApiModuleAlias
    ? createModuleOpenApiPageDescriptor(openApiModuleAlias)
    : tryPageDescriptorFromUrl(url, options);
  if (!descriptor) {
    return state;
  }

  const workspaceDescriptor = restoredWorkspaceViewDescriptor(descriptor);
  const effectiveDescriptor = workspaceDescriptor ?? descriptor;

  const explicitMenu = descriptor.menuId ? findMenuById(state.menus, descriptor.menuId) : undefined;
  const menu =
    explicitMenu && menuMatchesDescriptor(explicitMenu, effectiveDescriptor, options)
      ? explicitMenu
      : findMenuByDescriptor(state.menus, effectiveDescriptor, options);
  const target = menu ? getMenuNavigationTarget(menu) : undefined;
  const tab = workspaceDescriptor
    ? createDirectTab(workspaceDescriptor)
    : menu && target && isTabMenuTarget(target)
      ? createRestoredMenuTab(menu, target, effectiveDescriptor, options)
      : createDirectTab(effectiveDescriptor);
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
    return currentTab ? [currentTab] : includeMissingLockedTabs ? [lockedTab] : [];
  });
  return [...pinned, ...tabs.filter((tab) => !lockedKeys.has(tab.key))];
}

/** Restores only currently visible tab menus, rebuilding their descriptors from the active menu scheme. */
export function restoreLockedMenuTabs(
  lockedTabs: MenuTab[],
  menus: MenuTreeNode[],
  options: PageDescriptorResolveOptions = {},
): MenuTab[] {
  const availableMenus = new Map<string, { menu: MenuRecord; target: MenuNavigationTarget }>();
  collectTabMenus(menus, availableMenus);
  const restoredKeys = new Set<string>();
  return lockedTabs.flatMap((tab) => {
    const menuId = tab.pageDescriptor?.menuId ?? tab.target?.menuId;
    if (!menuId || restoredKeys.has(menuId)) return [];
    const available = availableMenus.get(menuId);
    if (!available) return [];
    restoredKeys.add(menuId);
    return [createMenuTab(available.menu, available.target, options)];
  });
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

function createDirectTab(descriptor: PageDescriptor): MenuTab {
  return {
    key: tabKeyOf(descriptor),
    title: descriptor.title ?? directTabTitleOf(descriptor),
    pageDescriptor: descriptor,
    restoreState: { url: pageDescriptorToUrl(descriptor) },
    closable: true,
  };
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
    pageDescriptor,
    restoreState: { url: pageDescriptorToUrl(pageDescriptor) },
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
      left.target.pageMode === right.target.pageMode &&
      left.target.defaultUiConfigId === right.target.defaultUiConfigId &&
      left.target.defaultQueryTemplateId === right.target.defaultQueryTemplateId
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
