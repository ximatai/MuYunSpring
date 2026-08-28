import { assert, it } from 'vitest';
import type { CurrentUser, MenuTab, MenuTreeNode } from '@muyun/web-contracts';
import {
  activeTabUrlOf,
  arrangeLockedMenuTabs,
  closeMenuTab,
  closeMenuTabs,
  loadWorkbenchStartupState,
  openDirectTab,
  openMenuTab,
  removeLockedMenuTabs,
  reorderMenuTabs,
  restoreLockedWorkbenchTabs,
  restoreSessionWorkbenchTabs,
  restoreWorkbenchStartupStateFromUrl,
  updateLockedMenuTabs,
} from '@/app/workbenchStartup.ts';
import { createMenuTab, getMenuNavigationTarget } from '@/platform-workbench/menuNavigation.ts';
import { presentWorkbenchRealtimeStatus } from '@/platform-workbench/realtimeStatus.ts';
import {
  configureWorkspaceViewContributions,
  createWorkspaceViewDescriptor,
} from '@/platform-workbench/workspaceViews.ts';

it('workbench realtime status presents transport state without claiming platform health', () => {
  assert.deepEqual(presentWorkbenchRealtimeStatus('connected'), {
    label: '实时连接正常',
    title: '与平台实时服务连接正常',
    tone: 'connected',
  });
  assert.deepEqual(presentWorkbenchRealtimeStatus('connecting'), {
    label: '实时连接中',
    title: '正在连接平台实时服务',
    tone: 'connecting',
  });
  assert.deepEqual(presentWorkbenchRealtimeStatus('disconnected'), {
    label: '实时连接已断开',
    title: '与平台实时服务的连接已断开，正在等待恢复',
    tone: 'disconnected',
  });
  assert.equal(presentWorkbenchRealtimeStatus('unavailable'), undefined);
});

const currentUser: CurrentUser = {
  userId: 'user-1',
  system: false,
};

const menus: MenuTreeNode[] = [
  {
    record: {
      id: 'root',
      schemeId: 'default',
      title: 'Root',
    },
    children: [
      {
        record: {
          id: 'nested',
          schemeId: 'default',
          parentId: 'root',
          title: 'Nested',
        },
        children: [
          {
            record: {
              id: 'metadata',
              schemeId: 'default',
              parentId: 'nested',
              title: 'Metadata',
              entryType: 'route',
              openMode: 'tab',
              moduleAlias: 'platform.metadata',
              route: '/platform/metadata',
            },
            children: [],
          },
        ],
      },
    ],
  },
  {
    record: {
      id: 'runtime',
      schemeId: 'default',
      title: 'Runtime',
      entryType: 'module',
      openMode: 'tab',
      moduleAlias: 'platform.runtime',
      pageMode: 'LIST',
      defaultUiConfigId: 'runtime-list-v1',
    },
    children: [],
  },
  {
    record: {
      id: 'metadata-shortcut',
      schemeId: 'default',
      title: 'Metadata Shortcut',
      entryType: 'route',
      openMode: 'tab',
      moduleAlias: 'platform.metadata',
      route: '/platform/metadata',
    },
    children: [],
  },
];

const platformAdminMenus: MenuTreeNode[] = [
  {
    record: {
      id: 'platform.menu.group.platform',
      schemeId: 'platform.menu_scheme.admin',
      parentId: 'ROOT',
      title: '平台管理',
      enabled: true,
      sortOrder: 10,
    },
    children: [
      {
        record: {
          id: 'platform.menu.group.config',
          schemeId: 'platform.menu_scheme.admin',
          parentId: 'platform.menu.group.platform',
          title: '平台配置与低代码运维',
          enabled: true,
          sortOrder: 10,
        },
        children: [
          {
            record: {
              id: 'platform.menu.module.platform.application',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.config',
              title: '应用管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'platform.application',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 10,
            },
            children: [],
          },
          {
            record: {
              id: 'platform.menu.module.platform.module',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.config',
              title: '模块管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'platform.module',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 20,
            },
            children: [],
          },
          {
            record: {
              id: 'platform.menu.module.platform.dictionary_category',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.config',
              title: '字典管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'platform.dictionary_category',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 50,
            },
            children: [],
          },
        ],
      },
      {
        record: {
          id: 'platform.menu.group.identity',
          schemeId: 'platform.menu_scheme.admin',
          parentId: 'platform.menu.group.platform',
          title: '组织与权限',
          enabled: true,
          sortOrder: 20,
        },
        children: [
          {
            record: {
              id: 'platform.menu.module.iam.tenant',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.identity',
              title: '租户管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'iam.tenant',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 10,
            },
            children: [],
          },
          {
            record: {
              id: 'platform.menu.module.iam.employee',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.identity',
              title: '职员管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'iam.employee',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 50,
            },
            children: [],
          },
          {
            record: {
              id: 'platform.menu.module.iam.role',
              schemeId: 'platform.menu_scheme.admin',
              parentId: 'platform.menu.group.identity',
              title: '角色管理',
              entryType: 'module',
              openMode: 'tab',
              moduleAlias: 'iam.role',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 70,
            },
            children: [],
          },
        ],
      },
      {
        record: {
          id: 'platform.menu.group.ops',
          schemeId: 'platform.menu_scheme.admin',
          parentId: 'platform.menu.group.platform',
          title: '平台运行运维',
          enabled: true,
          sortOrder: 30,
        },
        children: [],
      },
    ],
  },
];

function platformRouteTargetOf(tab: MenuTab | undefined) {
  const descriptor = tab?.pageDescriptor;
  if (!descriptor || descriptor.pageType !== 'platform-route') {
    throw new Error('Expected a platform route tab.');
  }
  return descriptor.target;
}

function businessRouteTargetOf(tab: MenuTab | undefined) {
  const descriptor = tab?.pageDescriptor;
  if (!descriptor || descriptor.pageType !== 'business-route') {
    throw new Error('Expected a business route tab.');
  }
  return descriptor.target;
}

it('loadWorkbenchStartupState creates the first available navigation tab', async () => {
  const state = await loadWorkbenchStartupState({
    sessionClient: {
      current: async () => currentUser,
    },
    menuClient: {
      mine: async () => ({ records: menus }),
    },
  });

  assert.equal(state.session.currentUser.userId, 'user-1');
  assert.equal(state.activeTabKey, 'menu:metadata');
  assert.deepEqual(
    state.tabs?.map((tab) => tab.key),
    ['menu:metadata'],
  );
});

it('loads tenant branding with the workbench session when the client supports it', async () => {
  const state = await loadWorkbenchStartupState({
    sessionClient: {
      current: async () => currentUser,
      tenantBranding: async () => ({ lightLogo: 'data:image/png;base64,bGlnaHQ=' }),
    },
    menuClient: {
      mine: async () => ({ records: menus }),
    },
  });

  assert.equal(state.session.tenantBranding?.lightLogo, 'data:image/png;base64,bGlnaHQ=');
});

it('continues workbench startup with the default mark when tenant branding is unavailable', async () => {
  const state = await loadWorkbenchStartupState({
    sessionClient: {
      current: async () => currentUser,
      tenantBranding: async () => Promise.reject(new Error('temporary branding outage')),
    },
    menuClient: {
      mine: async () => ({ records: menus }),
    },
  });

  assert.equal(state.session.tenantBranding, undefined);
  assert.equal(state.activeTabKey, 'menu:metadata');
});

it('loadWorkbenchStartupState skips disabled navigation menus', async () => {
  const state = await loadWorkbenchStartupState({
    sessionClient: {
      current: async () => currentUser,
    },
    menuClient: {
      mine: async () => ({
        records: [
          {
            record: {
              id: 'disabled-runtime',
              schemeId: 'default',
              title: 'Disabled Runtime',
              openMode: 'tab',
              moduleAlias: 'platform.runtime',
              enabled: false,
            },
            children: [],
          },
          ...menus,
        ],
      }),
    },
  });

  assert.equal(state.activeTabKey, 'menu:metadata');
  assert.deepEqual(
    state.tabs?.map((tab) => tab.key),
    ['menu:metadata'],
  );
});

it('loadWorkbenchStartupState skips window navigation menus for the initial tab', async () => {
  const state = await loadWorkbenchStartupState({
    sessionClient: {
      current: async () => currentUser,
    },
    menuClient: {
      mine: async () => ({
        records: [
          {
            record: {
              id: 'external-bi',
              schemeId: 'default',
              title: 'External BI',
              openMode: 'window',
              externalUrl: 'https://bi.example.com/report',
            },
            children: [],
          },
          ...menus,
        ],
      }),
    },
  });

  assert.equal(state.activeTabKey, 'menu:metadata');
  assert.deepEqual(
    state.tabs?.map((tab) => tab.key),
    ['menu:metadata'],
  );
});

it('loadWorkbenchStartupState accepts backend initialized platform admin menus', async () => {
  const state = await loadWorkbenchStartupState(
    {
      sessionClient: {
        current: async () => ({
          userId: 'platform.user.super_admin',
          username: 'admin',
          system: true,
        }),
      },
      menuClient: {
        mine: async () => ({ records: platformAdminMenus }),
      },
    },
    {
      businessModuleRoutes: {
        'iam.tenant': '/iam/tenants',
      },
    },
  );

  assert.equal(state.activeTabKey, 'menu:platform.menu.module.platform.application');
  assert.equal(state.tabs?.[0]?.title, '应用管理');
  assert.deepEqual(state.tabs?.[0]?.target, {
    menuId: 'platform.menu.module.platform.application',
    menuType: 'module',
    openMode: 'tab',
    moduleAlias: 'platform.application',
    pageMode: 'LIST',
    defaultUiConfigId: undefined,
    defaultQueryTemplateId: undefined,
    entryParamsJson: undefined,
  });
  assert.equal(state.tabs?.[0]?.pageDescriptor?.pageType, 'dynamic-module');
  assert.deepEqual(state.tabs?.[0]?.pageDescriptor?.target, {
    moduleAlias: 'platform.application',
    pageMode: 'LIST',
    defaultUiConfigId: undefined,
    defaultQueryTemplateId: undefined,
  });
});

it('loadWorkbenchStartupState resolves backend tenant module menu to business route', async () => {
  const state = await loadWorkbenchStartupState(
    {
      sessionClient: {
        current: async () => ({
          userId: 'platform.user.super_admin',
          username: 'admin',
          system: true,
        }),
      },
      menuClient: {
        mine: async () => ({
          records: [platformAdminMenus[0].children[1].children[0]],
        }),
      },
    },
    {
      businessModuleRoutes: {
        'iam.tenant': '/iam/tenants',
      },
    },
  );

  assert.equal(state.activeTabKey, 'menu:platform.menu.module.iam.tenant');
  assert.equal(state.tabs?.[0]?.title, '租户管理');
  assert.equal(state.tabs?.[0]?.pageDescriptor?.pageType, 'business-route');
  const tenantTarget = businessRouteTargetOf(state.tabs?.[0]);
  assert.equal(tenantTarget.route, '/iam/tenants');
  assert.equal(tenantTarget.moduleAlias, 'iam.tenant');
  assert.match(String(tenantTarget.query?.InstanceKey ?? ''), /^[0-9a-f-]{36}$/i);
});

it('openMenuTab reuses an existing tab instead of duplicating it', () => {
  const metadata = menus[0].children[0].children[0].record;
  const runtime = menus[1].record;
  const metadataTarget = getMenuNavigationTarget(metadata);
  const runtimeTarget = getMenuNavigationTarget(runtime);

  assert.ok(metadataTarget);
  assert.ok(runtimeTarget);

  const first = openMenuTab([], metadata, metadataTarget);
  const duplicate = openMenuTab(first.tabs, metadata, metadataTarget);
  const second = openMenuTab(duplicate.tabs, runtime, runtimeTarget);

  assert.equal(duplicate.tabs.length, 1);
  assert.equal(duplicate.activeTabKey, 'menu:metadata');
  assert.deepEqual(
    second.tabs.map((tab) => tab.key),
    ['menu:metadata', 'menu:runtime'],
  );
});

it('openMenuTab repairs a direct tab with the selected menu title without replacing its page instance', () => {
  const tenant = platformAdminMenus[0].children[1].children[0].record;
  const target = getMenuNavigationTarget(tenant);
  assert.ok(target);

  const direct = openDirectTab([], {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
    menuId: tenant.id,
    target: { moduleAlias: 'iam.tenant', pageMode: 'LIST' },
    params: { InstanceKey: 'tenant-page-instance' },
    tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
  });
  const repaired = openMenuTab(direct.tabs, tenant, target);

  assert.equal(repaired.tabs.length, 1);
  assert.equal(repaired.tabs[0]?.title, '租户管理');
  assert.equal(repaired.tabs[0]?.instanceKey, 'tenant-page-instance');
  assert.equal(repaired.tabs[0]?.pageDescriptor?.title, '租户管理');
  assert.equal(repaired.tabs[0]?.fullPath, '/iam/tenant');
});

it('closeMenuTab keeps active tab when closing an inactive tab', () => {
  const tabs = [
    {
      key: 'ROUTE:metadata',
      title: 'Metadata',
      target: getMenuNavigationTarget(menus[0].children[0].children[0].record),
    },
    { key: 'ROUTE:runtime', title: 'Runtime', target: getMenuNavigationTarget(menus[1].record) },
  ];

  const result = closeMenuTab(tabs, 'ROUTE:runtime', 'ROUTE:metadata');

  assert.equal(result.activeTabKey, 'ROUTE:runtime');
  assert.deepEqual(
    result.tabs.map((tab) => tab.key),
    ['ROUTE:runtime'],
  );
});

it('reorderMenuTabs only reorders the current session tab array', () => {
  const tabs = [
    { key: 'A', title: 'A' },
    { key: 'B', title: 'B' },
    { key: 'C', title: 'C' },
  ];

  assert.deepEqual(
    reorderMenuTabs(tabs, ['C', 'A', 'B']).map((tab) => tab.key),
    ['C', 'A', 'B'],
  );
  assert.strictEqual(reorderMenuTabs(tabs, ['A', 'C']), tabs);
});

it('closeMenuTab activates the neighboring tab when closing the active tab', () => {
  const tabs: MenuTab[] = [
    { key: 'A', title: 'A', target: { menuId: 'a', menuType: 'route', openMode: 'tab', route: '/a' } },
    { key: 'B', title: 'B', target: { menuId: 'b', menuType: 'route', openMode: 'tab', route: '/b' } },
    { key: 'C', title: 'C', target: { menuId: 'c', menuType: 'route', openMode: 'tab', route: '/c' } },
  ];

  const middle = closeMenuTab(tabs, 'B', 'B');
  const last = closeMenuTab(tabs, 'C', 'C');

  assert.equal(middle.activeTabKey, 'C');
  assert.equal(last.activeTabKey, 'B');
});

it('closeMenuTabs keeps non-closable tabs and chooses the nearest remaining active tab', () => {
  const tabs = [
    { key: 'home', title: '首页', closable: false },
    { key: 'A', title: 'A', closable: true },
    { key: 'B', title: 'B', closable: true },
    { key: 'C', title: 'C', closable: true },
  ];

  assert.deepEqual(closeMenuTabs(tabs, 'B', ['home', 'A', 'B']), {
    tabs: [
      { key: 'home', title: '首页', closable: false },
      { key: 'C', title: 'C', closable: true },
    ],
    activeTabKey: 'C',
  });
});

it('keeps persisted locked tabs on the left and preserves their own ordering', () => {
  const tabs = [
    { key: 'A', title: 'A' },
    { key: 'B', title: 'B' },
    { key: 'C', title: 'C' },
  ];
  const locked = updateLockedMenuTabs([], tabs[1]);
  const lockedInOrder = updateLockedMenuTabs(locked, tabs[0]);

  assert.deepEqual(
    arrangeLockedMenuTabs(tabs, lockedInOrder).map((tab) => tab.key),
    ['B', 'A', 'C'],
  );
  assert.deepEqual(
    arrangeLockedMenuTabs([{ key: 'C', title: 'C' }], lockedInOrder, false).map((tab) => tab.key),
    ['C'],
  );
  assert.deepEqual(
    reorderMenuTabs(tabs, ['C', 'B', 'A'], ['B']).map((tab) => tab.key),
    ['B', 'C', 'A'],
  );
  assert.deepEqual(
    removeLockedMenuTabs(lockedInOrder, ['B']).map((tab) => tab.key),
    ['A'],
  );
});

it('uses rebuilt locked menu facts to repair a shared URL tab while preserving its page instance', () => {
  const tenant = platformAdminMenus[0].children[1].children[0].record;
  const target = getMenuNavigationTarget(tenant);
  assert.ok(target);
  const lockedTab = createMenuTab(tenant, target);
  const sharedUrlTab = openDirectTab([], {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
    menuId: tenant.id,
    target: { moduleAlias: 'iam.tenant', pageMode: 'LIST' },
    params: { InstanceKey: 'shared-tenant-instance' },
    tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
  }).tabs;

  const arranged = arrangeLockedMenuTabs(sharedUrlTab, [lockedTab]);

  assert.equal(arranged[0]?.title, '租户管理');
  assert.equal(arranged[0]?.instanceKey, 'shared-tenant-instance');
  assert.equal(arranged[0]?.pageDescriptor?.title, '租户管理');
});

it('restores locked tabs only when their tab menu remains visible to the current account', () => {
  const available = restoreLockedWorkbenchTabs(
    [
      {
        key: 'menu:metadata',
        title: '旧名称',
        target: { menuId: 'metadata', menuType: 'route', openMode: 'tab', route: '/old' },
      },
      {
        key: 'menu:revoked',
        title: '已撤销',
        target: { menuId: 'revoked', menuType: 'route', openMode: 'tab', route: '/revoked' },
      },
    ],
    menus,
  );

  assert.deepEqual(
    available.map((tab) => [tab.key, tab.title, tab.target?.menuId]),
    [['menu:metadata', 'Metadata', 'metadata']],
  );
});

it('restores a direct in-app tab from the browser session while excluding external targets', () => {
  const restored = restoreSessionWorkbenchTabs(
    [
      {
        key: 'dynamic-module:iam.user:recordId=user-1',
        title: '用户详情',
        pageDescriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          target: { moduleAlias: 'iam.user', pageMode: 'DETAIL' },
          params: { recordId: 'user-1' },
          tabPolicy: { identity: 'by-params', closable: true, cacheable: true },
        },
      },
      {
        key: 'external',
        title: '外部页面',
        pageDescriptor: {
          pageType: 'remote-url',
          openMode: 'iframe',
          hostType: 'external-page-host',
          target: { url: 'https://example.com' },
          params: {},
          tabPolicy: { identity: 'by-target', closable: true, cacheable: false },
        },
      },
    ],
    menus,
  );

  assert.deepEqual(
    restored.map((tab) => tab.title),
    ['用户详情'],
  );
  assert.equal(restored[0]?.pageDescriptor?.target.moduleAlias, 'iam.user');
});

it('restores a locked registered workspace view without trusting its stale title', () => {
  const deviceView = {
    type: 'mr.device.detail',
    route: '/_workspace/mr.device.detail',
    moduleAlias: 'mr.device',
    component: { template: '<div />' },
    presentations: ['tab'] as const,
    titleOf: ({ recordId }: { recordId: string }) => `设备 ${recordId}`,
    parse: (query: Record<string, unknown>) =>
      typeof query.recordId === 'string' ? { recordId: query.recordId } : undefined,
  };
  configureWorkspaceViewContributions('locked-tab-test', [deviceView]);
  try {
    const descriptor = createWorkspaceViewDescriptor(deviceView, { recordId: 'device-1' }, 'tab', '过期标题');
    const restored = restoreLockedWorkbenchTabs(
      [{ key: 'stale-key', title: '旧标题', pageDescriptor: descriptor }],
      menus,
    );

    assert.deepEqual(
      restored.map((tab) => [tab.key, tab.title]),
      [
        [
          'business-route:/_workspace/mr.device.detail:recordId=device-1&workspacePresentation=tab&workspaceView=mr.device.detail',
          '设备 device-1',
        ],
      ],
    );
  } finally {
    configureWorkspaceViewContributions('locked-tab-test', []);
  }
});

it('activeTabUrlOf returns the active tab descriptor URL', () => {
  const metadata = menus[0].children[0].children[0].record;
  const target = getMenuNavigationTarget(metadata);

  assert.ok(target);

  const tab: MenuTab = {
    key: 'menu:metadata',
    title: 'Metadata',
    target,
    pageDescriptor: {
      pageType: 'platform-route',
      openMode: 'workbench-route',
      hostType: 'platform-route-host',
      menuId: 'metadata',
      target: { route: '/platform/metadata' },
      tabPolicy: { identity: 'by-menu' },
    },
  };

  assert.equal(
    activeTabUrlOf({
      session: { currentUser },
      menus,
      tabs: [tab],
      activeTabKey: tab.key,
    }),
    '/platform/metadata',
  );
});

it('activeTabUrlOf keeps new-window external links on workbench-owned URLs', () => {
  const tab: MenuTab = {
    key: 'menu:external-bi',
    title: 'BI',
    pageDescriptor: {
      pageType: 'external-link',
      openMode: 'new-window',
      hostType: 'external-page-host',
      menuId: 'external-bi',
      target: { url: 'https://bi.example.com/report' },
      tabPolicy: { identity: 'by-menu' },
    },
  };

  assert.equal(
    activeTabUrlOf({
      session: { currentUser },
      menus,
      tabs: [tab],
      activeTabKey: tab.key,
    }),
    '/_platform/external?mode=new-window&url=https%3A%2F%2Fbi.example.com%2Freport',
  );
});

it('restoreWorkbenchStartupStateFromUrl activates the matching menu tab', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/platform/metadata');

  assert.equal(restored.activeTabKey, 'menu:metadata');
  assert.deepEqual(
    restored.tabs?.map((tab) => tab.title),
    ['Metadata'],
  );
  assert.equal(restored.tabs?.[0]?.target?.menuId, 'metadata');
});

it('restoreWorkbenchStartupStateFromUrl matches dynamic module menus with module context', () => {
  const state = {
    session: { currentUser },
    menus: [
      {
        record: {
          id: 'organization',
          schemeId: 'default',
          title: '机构管理',
          openMode: 'tab' as const,
          entryType: 'module' as const,
          moduleAlias: 'iam.organization',
        },
        children: [],
      },
    ],
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/iam/organization');

  assert.equal(restored.activeTabKey, 'menu:organization');
  assert.equal(restored.tabs?.[0]?.title, '机构管理');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.pageType, 'dynamic-module');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.target.moduleAlias, 'iam.organization');
  assert.equal(activeTabUrlOf(restored), '/iam/organization');
});

it('restoreWorkbenchStartupStateFromUrl preserves query when URL matches a menu tab', () => {
  const metadata = menus[0].children[0].children[0].record;
  const target = getMenuNavigationTarget(metadata);
  assert.ok(target);

  const defaultTab = openMenuTab([], metadata, target).tabs[0];
  const state = {
    session: { currentUser },
    menus,
    tabs: [defaultTab],
    activeTabKey: defaultTab.key,
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/platform/metadata?view=advanced');

  assert.equal(restored.activeTabKey, 'menu:metadata');
  assert.equal(restored.tabs?.length, 1);
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.params?.view, 'advanced');
  assert.equal(activeTabUrlOf(restored), '/platform/metadata?view=advanced');
});

it('restoreWorkbenchStartupStateFromUrl prefers explicit menu id when routes are duplicated', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/platform/metadata?menu=metadata-shortcut');

  assert.equal(restored.activeTabKey, 'menu:metadata-shortcut');
  assert.equal(restored.tabs?.[0]?.title, 'Metadata Shortcut');
  assert.equal(restored.tabs?.[0]?.target?.menuId, 'metadata-shortcut');
});

it('restoreWorkbenchStartupStateFromUrl ignores explicit menu id when target does not match', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/platform/metadata?_muyunMenuId=runtime');

  assert.equal(restored.activeTabKey, 'menu:metadata');
  assert.equal(restored.tabs?.[0]?.title, 'Metadata');
  assert.equal(restored.tabs?.[0]?.target?.menuId, 'metadata');
});

it('activeTabUrlOf returns undefined when no active tab remains', () => {
  assert.equal(
    activeTabUrlOf({
      session: { currentUser },
      menus,
      tabs: [],
      activeTabKey: undefined,
    }),
    undefined,
  );
});

it('openDirectTab keeps authorization pages for different roles separate', () => {
  const first = openDirectTab([], {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    title: '授权 - 角色 A',
    target: { route: '/iam/role-authorization', query: { roleId: 'role-a' } },
    params: { roleId: 'role-a' },
    tabPolicy: { identity: 'by-params' },
  });
  const second = openDirectTab(first.tabs, {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    title: '授权 - 角色 B',
    target: { route: '/iam/role-authorization', query: { roleId: 'role-b' } },
    params: { roleId: 'role-b' },
    tabPolicy: { identity: 'by-params' },
  });

  assert.equal(second.tabs.length, 2);
  assert.notEqual(first.activeTabKey, second.activeTabKey);
  assert.match(
    second.activeTabKey,
    /^business-route:\/iam\/role-authorization:roleId=role-b:InstanceKey:[0-9a-f-]{36}$/i,
  );
});

it('openDirectTab reports reuse when the stable work view tab already exists', () => {
  const descriptor = {
    pageType: 'business-route' as const,
    openMode: 'workbench-route' as const,
    hostType: 'business-route-host' as const,
    title: '职员详情',
    target: {
      route: '/iam/employees',
      query: { workspaceView: 'iam.employee.detail', recordId: 'employee-1' },
    },
    params: { workspaceView: 'iam.employee.detail', recordId: 'employee-1' },
    tabPolicy: { identity: 'by-params' as const },
  };
  const first = openDirectTab([], descriptor);
  const reused = openDirectTab(first.tabs, descriptor);

  assert.equal(first.created, true);
  assert.equal(reused.created, false);
  assert.equal(reused.tabs.length, 1);
});

it('openDirectTab keeps the InstanceKey internal to the workbench tab', () => {
  const opened = openDirectTab([], {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    title: '角色授权',
    target: { route: '/iam/role-authorization', query: { roleId: 'role-a' } },
    params: { roleId: 'role-a' },
    tabPolicy: { identity: 'by-params' },
  });

  const tab = opened.tabs[0];
  assert.match(tab?.instanceKey ?? '', /^[0-9a-f-]{36}$/i);
  assert.equal(new URL(tab?.fullPath ?? '', 'http://muyun.local').searchParams.get('InstanceKey'), null);
});

it('restoreWorkbenchStartupStateFromUrl creates direct tab when URL has no menu match', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/crm/customer/list?status=active');

  assert.equal(restored.activeTabKey, 'platform-route:/crm/customer/list');
  assert.equal(restored.tabs?.[0]?.target, undefined);
  assert.equal(platformRouteTargetOf(restored.tabs?.[0]).route, '/crm/customer/list');
});

it('restores a readable module URL through the matching visible menu without an instance marker', () => {
  const restored = restoreWorkbenchStartupStateFromUrl(
    {
      session: { currentUser },
      menus: platformAdminMenus,
      tabs: [],
    },
    '/config/modules',
    { dynamicModuleRoutes: { '/config/modules': 'platform.module' } },
  );

  assert.equal(restored.activeTabKey, 'menu:platform.menu.module.platform.module');
  assert.equal(restored.tabs?.[0]?.title, '模块管理');
  assert.equal(restored.tabs?.[0]?.fullPath, '/config/modules');
});

it('restoreWorkbenchStartupStateFromUrl restores a module OpenAPI document as a direct tab', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
    activeTabKey: undefined,
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/openapi/education.teacher');

  assert.equal(restored.tabs?.length, 1);
  assert.equal(restored.tabs?.[0]?.title, 'education.teacher.OpenAPI');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.target.moduleAlias, 'education.teacher');
  assert.equal(activeTabUrlOf(restored), '/openapi/education.teacher');
});

it('restoreWorkbenchStartupStateFromUrl falls back to the menu route when no workspace view is declared', () => {
  const state = {
    session: { currentUser },
    menus: [
      {
        record: {
          id: 'iam.employee.menu',
          schemeId: 'default',
          title: '职员管理',
          openMode: 'tab' as const,
          route: '/iam/employees',
          moduleAlias: 'iam.employee',
        },
        children: [],
      },
    ],
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(
    state,
    '/iam/employees?recordId=employee-1&workspacePresentation=tab&workspaceView=iam.employee.detail&_muyunTitle=Alice',
    { businessRoutePrefixes: ['/iam/employees'] },
  );

  assert.equal(restored.activeTabKey, 'business-route:/iam/employees');
  assert.equal(restored.tabs?.[0]?.title, '/iam/employees');
  assert.equal(restored.tabs?.[0]?.target, undefined);
  assert.equal(businessRouteTargetOf(restored.tabs?.[0]).query?.workspaceView, 'iam.employee.detail');
});

it('restoreWorkbenchStartupStateFromUrl does not restore window menus as workbench menu tabs', () => {
  const state = {
    session: { currentUser },
    menus: [
      {
        record: {
          id: 'external-bi',
          schemeId: 'default',
          title: 'External BI',
          openMode: 'window' as const,
          moduleAlias: 'ops.report',
          externalUrl: 'https://bi.example.com/report',
        },
        children: [],
      },
    ],
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(
    state,
    '/_platform/external?_muyunMenuId=external-bi&_muyunTitle=External%20BI&mode=new-window&url=https%3A%2F%2Fbi.example.com%2Freport',
  );

  assert.equal(restored.activeTabKey, 'menu:external-bi');
  assert.equal(restored.tabs?.[0]?.target, undefined);
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.menuId, 'external-bi');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.openMode, 'new-window');
});

it('restoreWorkbenchStartupStateFromUrl keeps current state for invalid workbench-owned URLs', () => {
  const metadata = menus[0].children[0].children[0].record;
  const target = getMenuNavigationTarget(metadata);
  assert.ok(target);

  const defaultTab = openMenuTab([], metadata, target).tabs[0];
  const state = {
    session: { currentUser },
    menus,
    tabs: [defaultTab],
    activeTabKey: defaultTab.key,
  };

  for (const url of ['/_platform/external', '/_platform/workspace']) {
    const restored = restoreWorkbenchStartupStateFromUrl(state, url);

    assert.equal(restored.activeTabKey, 'menu:metadata');
    assert.equal(restored.tabs?.length, 1);
    assert.equal(restored.tabs?.[0]?.key, 'menu:metadata');
  }
});

it('restoreWorkbenchStartupStateFromUrl keeps empty workspace for invalid workbench-owned URLs', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
    activeTabKey: undefined,
  };

  for (const url of ['/platform/dynamic', '/platform/dynamic//list']) {
    const restored = restoreWorkbenchStartupStateFromUrl(state, url);

    assert.equal(restored.activeTabKey, undefined);
    assert.deepEqual(restored.tabs, []);
  }
});

it('restoreWorkbenchStartupStateFromUrl matches dynamic menu without title query', () => {
  const state = {
    session: { currentUser },
    menus,
    tabs: [],
  };

  const restored = restoreWorkbenchStartupStateFromUrl(state, '/platform/runtime');

  assert.equal(restored.activeTabKey, 'menu:runtime');
  assert.equal(restored.tabs?.[0]?.title, 'Runtime');
  assert.equal(restored.tabs?.[0]?.target?.menuId, 'runtime');
});

it('restoreWorkbenchStartupStateFromUrl retains the visible menu title for a canonical dynamic URL', () => {
  const restored = restoreWorkbenchStartupStateFromUrl(
    {
      session: { currentUser },
      menus,
      tabs: [],
    },
    '/platform/runtime',
  );

  assert.equal(restored.tabs?.[0]?.title, 'Runtime');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.title, 'Runtime');
});

it('restoreWorkbenchStartupStateFromUrl does not trust a shared dynamic URL title over the visible menu title', () => {
  const restored = restoreWorkbenchStartupStateFromUrl(
    {
      session: { currentUser },
      menus: platformAdminMenus,
      tabs: [],
    },
    '/iam/tenant?_muyunTitle=Forged',
  );

  assert.equal(restored.tabs?.[0]?.title, '租户管理');
  assert.equal(restored.tabs?.[0]?.pageDescriptor?.title, '租户管理');
});
