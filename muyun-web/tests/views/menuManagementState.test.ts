import { assert, it } from 'vitest';
import {
  createMenuManagementState,
  defaultMenuSchemeScope,
  normalizeMenuDraft,
  normalizeSchemeDraft,
  validateMenu,
} from '@/views/menuManagementState.ts';
import type { MenuRecord, MenuScheme } from '@/web-contracts/index.ts';
import type { ModuleContext } from '@/web-core/module/moduleContext.ts';

it('menu management normalizes scheme identity only on create', () => {
  const created = normalizeSchemeDraft(
    { alias: ' admin ', title: ' 管理菜单 ', scopeType: 'system', scopeId: '  ' },
    undefined,
    'create',
  );
  const updated = normalizeSchemeDraft(
    { id: 'scheme-1', alias: ' changed ', title: ' 管理菜单 ', scopeType: 'tenant' },
    { id: 'scheme-1', alias: 'admin', title: '管理菜单', scopeType: 'tenant' },
    'edit',
  );

  assert.deepEqual(created, {
    id: 'admin',
    alias: 'admin',
    title: '管理菜单',
    scopeType: 'system',
    scopeId: undefined,
  });
  assert.equal(updated.id, 'scheme-1');
  assert.equal(updated.alias, 'admin');
});

it('menu management defaults scheme scope from current user identity', () => {
  assert.deepEqual(defaultMenuSchemeScope({ userId: 'admin', username: 'admin', system: true }), {
    tenantId: undefined,
    scopeType: 'system',
    scopeId: 'system',
  });
  assert.deepEqual(
    defaultMenuSchemeScope({
      userId: 'tenant-admin',
      username: 'admin',
      tenantId: 'tenant-a',
      system: false,
    }),
    {
      tenantId: 'tenant-a',
      scopeType: 'tenant',
      scopeId: 'tenant-a',
    },
  );
  assert.deepEqual(
    defaultMenuSchemeScope({
      userId: 'org-admin',
      username: 'admin',
      tenantId: 'tenant-a',
      organizationId: 'org-a',
      system: false,
    }),
    {
      tenantId: 'tenant-a',
      scopeType: 'tenant',
      scopeId: 'tenant-a',
    },
  );
});

it('menu management keeps entry fields only when module alias is bound', () => {
  const moduleMenu = normalizeMenuDraft(baseMenu({ moduleAlias: ' platform.menu ' }), 'scheme-1');
  const routeMenu = normalizeMenuDraft(baseMenu({ moduleAlias: ' platform.menu_scheme ' }), 'scheme-1');
  const linkMenu = normalizeMenuDraft(baseMenu({ moduleAlias: ' platform.docs ' }), 'scheme-1');
  const groupMenu = normalizeMenuDraft(baseMenu({ moduleAlias: undefined }), 'scheme-1');

  assert.equal(moduleMenu.moduleAlias, 'platform.menu');
  assert.equal(moduleMenu.route, undefined);
  assert.equal(moduleMenu.externalUrl, undefined);
  assert.equal(moduleMenu.openMode, 'tab');
  assert.equal(moduleMenu.pageMode, 'LIST');
  assert.equal(moduleMenu.defaultUiConfigId, 'ui-1');
  assert.equal(moduleMenu.defaultQueryTemplateId, 'query-1');
  assert.equal(routeMenu.moduleAlias, 'platform.menu_scheme');
  assert.equal(routeMenu.route, undefined);
  assert.equal(linkMenu.moduleAlias, 'platform.docs');
  assert.equal(linkMenu.externalUrl, undefined);
  assert.equal(linkMenu.openMode, 'tab');
  assert.equal(groupMenu.openMode, undefined);
  assert.equal(groupMenu.moduleAlias, undefined);
});

it('menu management validates open mode only for bound module entry', () => {
  assert.equal(validateMenu(baseMenu({ moduleAlias: undefined })), undefined);
  assert.equal(
    validateMenu(baseMenu({ moduleAlias: 'platform.menu', openMode: undefined })),
    '入口菜单必须选择打开方式',
  );
  assert.equal(validateMenu(baseMenu({ moduleAlias: 'platform.menu' })), undefined);
});

it('menu management writes menus through the current scoped menu context', async () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
  );

  state.handleSchemesLoaded([
    { id: 'scheme-1', alias: 'admin', title: '管理菜单', scopeType: 'system', enabled: true },
  ]);

  state.startCreateRootMenu();
  state.menuDraft.value = baseMenu({
    id: 'menu-created',
    schemeId: 'scheme-1',
    title: '菜单',
    moduleAlias: 'platform.menu_scheme',
  });
  await state.saveMenu();

  state.handleMenusLoaded([
    {
      id: 'menu-created',
      schemeId: 'scheme-1',
      title: '菜单',
      moduleAlias: 'platform.menu_scheme',
      openMode: 'tab',
      enabled: true,
    },
  ]);
  await state.toggleMenuEnabled();
  await state.removeSelectedMenu();

  assert.deepEqual(menuContext.calls, [
    ['insert', 'menu-created'],
    ['disable', 'menu-created'],
    ['view', 'menu-created'],
    ['delete', 'menu-created'],
  ]);
  assert.deepEqual(schemeContext.calls, []);
});

it('menu management starts scheme creation with current user default scope', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
    {
      currentUser: () => ({ userId: 'admin', username: 'admin', system: true }),
    },
  );

  state.startCreateScheme();

  assert.equal(state.schemeDraft.value.scopeType, 'system');
  assert.equal(state.schemeDraft.value.scopeId, 'system');
  assert.equal(state.schemeDraft.value.tenantId, undefined);
});

it('menu management cancel closes scheme creation panel', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
    {
      currentUser: () => ({ userId: 'admin', username: 'admin', system: true }),
    },
  );

  state.startCreateScheme();
  state.schemeDraft.value.title = '临时方案';
  state.cancelSchemeEdit();

  assert.equal(state.schemeMode.value, 'view');
  assert.equal(state.selectedScheme.value, undefined);
  assert.equal(state.schemeDraft.value.title, '');
  assert.equal(state.schemeDraft.value.scopeType, 'system');
});

it('menu management cancel restores selected scheme edit draft', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
  );
  const selected = {
    id: 'scheme-1',
    alias: 'admin',
    title: '管理菜单',
    scopeType: 'system' as const,
    scopeId: 'system',
    enabled: true,
  };

  state.handleSchemesLoaded([selected]);
  state.startEditScheme();
  state.schemeDraft.value.title = '已修改';
  state.cancelSchemeEdit();

  assert.equal(state.schemeMode.value, 'view');
  assert.equal(state.schemeDraft.value.title, '管理菜单');
  assert.equal(state.selectedScheme.value?.id, 'scheme-1');
});

it('menu management cancel restores selected parent after child creation', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
  );
  const parent = baseMenu({ id: 'menu-parent', title: '父菜单', moduleAlias: undefined });

  state.handleSchemesLoaded([
    { id: 'scheme-1', alias: 'admin', title: '管理菜单', scopeType: 'system', enabled: true },
  ]);
  state.handleMenusLoaded([parent]);
  state.startCreateChildMenu(parent);

  assert.equal(state.menuMode.value, 'create-child');
  assert.equal(state.selectedMenu.value?.id, 'menu-parent');
  assert.equal(state.menuDraft.value.parentId, 'menu-parent');

  state.menuDraft.value.title = '临时子菜单';
  state.cancelMenuEdit();

  assert.equal(state.menuMode.value, 'view');
  assert.equal(state.selectedMenu.value?.id, 'menu-parent');
  assert.equal(state.menuDraft.value.title, '父菜单');
});

it('menu management cancel restores selected menu after root creation', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
  );
  const selected = baseMenu({ id: 'menu-selected', title: '当前菜单', moduleAlias: undefined });

  state.handleSchemesLoaded([
    { id: 'scheme-1', alias: 'admin', title: '管理菜单', scopeType: 'system', enabled: true },
  ]);
  state.handleMenusLoaded([selected]);
  state.startCreateRootMenu();

  assert.equal(state.menuMode.value, 'create-root');
  assert.equal(state.selectedMenu.value?.id, 'menu-selected');
  assert.equal(state.menuDraft.value.parentId, undefined);

  state.menuDraft.value.title = '临时根菜单';
  state.cancelMenuEdit();

  assert.equal(state.menuMode.value, 'view');
  assert.equal(state.selectedMenu.value?.id, 'menu-selected');
  assert.equal(state.menuDraft.value.title, '当前菜单');
});

it('menu management keeps create draft when menus reload', () => {
  const schemeContext = createFakeContext<MenuScheme>('platform.menu_scheme');
  const menuContext = createFakeContext<MenuRecord>('platform.menu-scheme/scheme-1/menus');
  const state = createMenuManagementState(
    schemeContext,
    () => menuContext,
    async () => true,
  );
  const selected = baseMenu({ id: 'menu-selected', title: '当前菜单', moduleAlias: undefined });

  state.handleSchemesLoaded([
    { id: 'scheme-1', alias: 'admin', title: '管理菜单', scopeType: 'system', enabled: true },
  ]);
  state.handleMenusLoaded([selected]);
  state.startCreateRootMenu();
  state.menuDraft.value.title = '未保存根菜单';
  state.handleMenusLoaded([baseMenu({ id: 'menu-selected', title: '刷新后的菜单', moduleAlias: undefined })]);

  assert.equal(state.menuMode.value, 'create-root');
  assert.equal(state.selectedMenu.value?.title, '刷新后的菜单');
  assert.equal(state.menuDraft.value.title, '未保存根菜单');
});

function baseMenu(overrides: Partial<MenuRecord>): MenuRecord {
  return {
    id: 'menu-1',
    schemeId: 'scheme-1',
    title: '菜单',
    openMode: 'tab',
    moduleAlias: 'dirty.module',
    route: '/dirty',
    externalUrl: 'https://dirty.example.com',
    pageMode: 'LIST',
    defaultUiConfigId: 'ui-1',
    defaultQueryTemplateId: 'query-1',
    entryParamsJson: '{}',
    ...overrides,
  };
}

function createFakeContext<TRecord extends { id?: string }>(moduleAlias: string) {
  const calls: unknown[][] = [];
  const records = new Map<string, TRecord>();
  const crud = {
    querySchema: async () => ({ fields: [] }),
    query: async () => ({ records: [], total: 0, pageNum: 1, pageSize: 10, pages: 0, totalKnown: true }),
    view: async (id: string) => {
      calls.push(['view', id]);
      return records.get(id) ?? ({ id } as TRecord);
    },
    insert: async (record: TRecord) => {
      calls.push(['insert', record.id]);
      if (record.id) {
        records.set(record.id, record);
      }
      return { record };
    },
    update: async (id: string, record: TRecord) => {
      calls.push(['update', id]);
      records.set(id, { ...record, id });
      return { record };
    },
    delete: async (id: string) => {
      calls.push(['delete', id]);
      records.delete(id);
      return 1;
    },
    enable: async (id: string) => {
      calls.push(['enable', id]);
      return 1;
    },
    disable: async (id: string) => {
      calls.push(['disable', id]);
      return 1;
    },
  };
  const context = {
    moduleAlias,
    runtime: {
      ready: Promise.resolve(),
      refresh: async () => undefined,
      snapshot: () => undefined,
      action: () => undefined,
      can: () => true,
      hasAbility: () => true,
    },
    abilities: {
      crud: () => crud,
      tree: () => crud,
      enable: () => crud,
      tryCrud: () => crud,
      tryTree: () => crud,
      tryEnable: () => crud,
      has: () => true,
      hasCrud: () => true,
      hasTree: () => true,
      hasEnable: () => true,
    },
    action: () => undefined,
    can: () => true,
    calls,
  };
  return context as unknown as ModuleContext<TRecord> & { calls: unknown[][] };
}
