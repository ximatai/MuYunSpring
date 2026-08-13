import { assert, it } from 'vitest';
import type { Tenant } from '@/web-contracts/index.ts';
import type { ModuleContext, ModuleRuntimeContextState } from '@/web-core/index.ts';
import { createTenantManagementState } from '@/views/tenantManagementState.ts';

it('tenant management state selects first loaded tenant and creates records with alias as id', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, sortOrder: 10 } };
    },
  });
  const state = createTenantManagementState(context, async () => true);

  state.handleListLoaded([{ id: 'platform', alias: 'platform', title: '平台', enabled: true }]);
  state.startCreate();
  state.draft.value.alias = '  tenant_a  ';
  state.draft.value.title = '  租户 A  ';

  await state.save();

  assert.equal(state.selected.value?.id, 'tenant_a');
  assert.equal(state.selected.value?.alias, 'tenant_a');
  assert.equal(state.mode.value, 'view');
  assert.equal(state.reloadKey.value, 1);
  assert.deepEqual(calls[0], {
    id: 'tenant_a',
    alias: 'tenant_a',
    enabled: true,
    title: '租户 A',
  });
});

it('tenant management state keeps existing alias stable while editing title', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    update: async (id, record) => {
      calls.push({ id, record });
      return { record: { ...record, title: '身份权限' } };
    },
  });
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true });
  state.startEdit();
  state.draft.value.alias = 'changed';
  state.draft.value.title = '身份权限';
  await state.save();

  assert.deepEqual(calls[0], {
    id: 'platform',
    record: {
      id: 'platform',
      alias: 'platform',
      title: '身份权限',
      enabled: true,
    },
  });
  assert.equal(state.selected.value?.alias, 'platform');
});

it('tenant management state toggles enable state and refreshes selected record', async () => {
  const calls: Array<{ action: string; id: string; request?: unknown }> = [];
  const context = createContext({
    disable: async (id, request) => {
      calls.push({ action: 'disable', id, request });
      return 1;
    },
    view: async (id) => {
      calls.push({ action: 'view', id });
      return { id, alias: id, title: '租户 A', enabled: false, version: 1 };
    },
  });
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'tenant_a', alias: 'tenant_a', title: '租户 A', enabled: true, version: 0 });
  await state.toggleEnabled();

  assert.deepEqual(calls, [
    { action: 'disable', id: 'tenant_a', request: { version: 0 } },
    { action: 'view', id: 'tenant_a' },
  ]);
  assert.equal(state.selected.value?.enabled, false);
  assert.equal(state.reloadKey.value, 1);
});

it('tenant management state cancels creation back to selected tenant', () => {
  const context = createContext();
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'tenant_a', alias: 'tenant_a', title: '租户 A', enabled: true, version: 0 });
  state.startCreate();
  state.cancelEdit();

  assert.equal(state.selected.value?.id, 'tenant_a');
  assert.equal(state.draft.value.id, 'tenant_a');
  assert.equal(state.mode.value, 'view');
});

it('tenant management state respects delete confirmation result', async () => {
  const calls: Array<{ id: string; request: unknown }> = [];
  const context = createContext({
    delete: async (id, request) => {
      calls.push({ id, request });
      return 1;
    },
  });
  let confirmed = false;
  const state = createTenantManagementState(context, async () => confirmed);

  state.handleSelect({ id: 'tenant_a', alias: 'tenant_a', title: '租户 A', enabled: true, version: 0 });
  await state.removeSelected();

  assert.deepEqual(calls, []);
  assert.equal(state.selected.value?.id, 'tenant_a');

  confirmed = true;
  await state.removeSelected();

  assert.deepEqual(calls, [{ id: 'tenant_a', request: { version: 0 } }]);
  assert.equal(state.selected.value, undefined);
  assert.equal(state.mode.value, 'create');
});

it('tenant deletion requires the selected tenant alias in its confirmation options', async () => {
  const confirmations: unknown[] = [];
  const context = createContext();
  const state = createTenantManagementState(context, async (options) => {
    confirmations.push(options);
    return false;
  });

  state.handleSelect({ id: 'demo', alias: 'demo', title: '演示租户', enabled: true });
  await state.removeSelected();

  assert.deepEqual(confirmations, [
    {
      title: '删除租户',
      content: '确认删除租户「演示租户」？',
      okText: '删除',
      danger: true,
      requiredText: 'demo',
    },
  ]);
});

it('tenant deletion stops when the selected record changes during confirmation', async () => {
  const calls: string[] = [];
  let resolveConfirmation!: (confirmed: boolean) => void;
  const context = createContext({
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  const state = createTenantManagementState(
    context,
    () => new Promise<boolean>((resolve) => (resolveConfirmation = resolve)),
  );

  state.handleSelect({ id: 'tenant_a', alias: 'tenant_a', title: '租户 A', enabled: true });
  const removing = state.removeSelected();
  state.handleSelect({ id: 'tenant_b', alias: 'tenant_b', title: '租户 B', enabled: true });
  resolveConfirmation(true);
  await removing;

  assert.deepEqual(calls, []);
  assert.equal(state.selected.value?.id, 'tenant_b');
  assert.equal(state.actionError.value, '待删除记录已变化，请重新确认删除操作');
});

it('tenant management state treats platform alias as ordinary tenant', async () => {
  const calls: string[] = [];
  const context = createContext({
    disable: async (id) => {
      calls.push(`disable:${id}`);
      return 1;
    },
    view: async (id) => ({ id, alias: id, title: '平台', enabled: false, version: 1 }),
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });

  assert.equal(state.canDelete.value, true);
  assert.equal(state.canEnable.value, true);

  await state.toggleEnabled();

  await state.removeSelected();
  assert.deepEqual(calls, ['disable:platform', 'delete:platform']);
});

it('tenant management state allows saving disabled platform alias', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    update: async (id, record) => {
      calls.push({ id, record });
      return { record };
    },
  });
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台租户', enabled: true });
  state.startEdit();
  state.draft.value.enabled = false;
  await state.save();

  assert.equal(state.actionError.value, undefined);
  assert.deepEqual(calls, [
    {
      id: 'platform',
      record: {
        id: 'platform',
        alias: 'platform',
        title: '平台租户',
        enabled: false,
      },
    },
  ]);
});

it('tenant management state does not enter create mode without create permission', async () => {
  const context = createContext({}, (actionCode) => actionCode !== 'create');
  const state = createTenantManagementState(context, async () => true);

  state.handleListLoaded([]);

  assert.equal(state.canCreate.value, false);
  assert.equal(state.mode.value, 'view');

  state.startCreate();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.actionError.value, '当前用户无权新建租户');
});

it('tenant management state keeps an empty readonly data set in view mode', () => {
  const context = createContext();
  const state = createTenantManagementState(context, async () => true);

  state.startCreate();
  state.draft.value.alias = 'should-not-survive';
  state.handleReadonlyListLoaded([]);

  assert.equal(state.selected.value, undefined);
  assert.deepEqual(state.draft.value, { alias: '', title: '', enabled: true });
  assert.equal(state.mode.value, 'view');
});

it('tenant management state selects readonly data without enabling editing', () => {
  const context = createContext();
  const state = createTenantManagementState(context, async () => true);

  state.handleReadonlyListLoaded([
    { id: 'deleted_tenant', alias: 'deleted_tenant', title: '已删除租户', enabled: true },
  ]);

  assert.equal(state.selected.value?.id, 'deleted_tenant');
  assert.equal(state.draft.value.id, 'deleted_tenant');
  assert.equal(state.mode.value, 'view');
});

it('tenant management state stays readonly after deleting last tenant without create permission', async () => {
  const calls: string[] = [];
  const context = createContext(
    {
      delete: async (id) => {
        calls.push(`delete:${id}`);
        return 1;
      },
    },
    (actionCode) => actionCode !== 'create',
  );
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'tenant_a', alias: 'tenant_a', title: '租户 A', enabled: true, version: 0 });
  await state.removeSelected();

  assert.deepEqual(calls, ['delete:tenant_a']);
  assert.equal(state.selected.value, undefined);
  assert.equal(state.mode.value, 'view');
});

it('tenant management state exposes action authorization flags', () => {
  const context = createContext({}, (actionCode) => actionCode === 'view');
  const state = createTenantManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true });

  assert.equal(state.canCreate.value, false);
  assert.equal(state.canUpdate.value, false);
  assert.equal(state.canDelete.value, false);
  assert.equal(state.canEnable.value, false);
  assert.equal(state.canMutate.value, false);
});

function createContext(
  overrides: Partial<ModuleContext<Tenant>['crud']> = {},
  canAction: (actionCode: string) => boolean | undefined = () => true,
): ModuleContext<Tenant> {
  const crud: ModuleContext<Tenant>['crud'] = {
    querySchema: async () => ({
      scopeName: 'iam.tenant',
      quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
      fields: [],
      externalCriteria: [],
      defaultSorts: [],
    }),
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id, alias: id, title: '平台', enabled: true }),
    insert: async (record) => ({ record }),
    update: async (_id, record) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
    ...overrides,
  };
  const enable = {
    enable: crud.enable,
    disable: crud.disable,
  };
  const tree = {
    ...crud,
    tree: async () => ({ records: [] }),
    treeFlat: async () => ({ records: [] }),
    subtree: async () => ({ records: [] }),
    sort: async () => 1,
  };
  return {
    moduleAlias: 'iam.tenant',
    http: { request: async () => undefined as never },
    crud,
    runtime: fakeRuntimeState(),
    abilities: {
      crud: () => crud,
      tree: () => tree,
      enable: () => enable,
      tryCrud: () => crud,
      tryTree: () => undefined,
      tryEnable: () => enable,
      has: () => undefined,
      hasCrud: () => undefined,
      hasTree: () => undefined,
      hasEnable: () => undefined,
    },
    action: () => undefined,
    runtimeAction: () => undefined,
    can: canAction,
    recordActions: async (recordId) => ({ recordId, actions: [] }),
    recordActionsSnapshot: () => undefined,
  };
}

function fakeRuntimeState(): ModuleRuntimeContextState {
  const context = {
    moduleAlias: 'iam.tenant',
    capabilities: ['CRUD', 'ENABLE', 'SORT'],
    abilities: ['crud', 'enable', 'sort'],
    actions: [],
    uiDescriptor: {
      schemaVersion: 'module-ui.v2',
      moduleAlias: 'iam.tenant',
      views: [],
      actions: [
        {
          actionCode: 'delete',
          confirmation: { mode: 'typedText' as const, requiredField: 'alias' },
        },
      ],
    },
  };
  return {
    ready: Promise.resolve(context),
    load: async () => context,
    snapshot: () => context,
    error: () => undefined,
    hasAbility: () => undefined,
    action: () => undefined,
    runtimeAction: () => undefined,
    can: () => undefined,
    recordActions: async (recordId) => ({ recordId, actions: [] }),
    recordActionsSnapshot: () => undefined,
  };
}
