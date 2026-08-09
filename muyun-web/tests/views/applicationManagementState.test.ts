import { assert, it } from 'vitest';
import type { Application } from '@/web-contracts/index.ts';
import { platformActionResultReactionTypes } from '@/platform-components/platformActionResultFeedback.ts';
import {
  AppError,
  platformErrorCodes,
  type ModuleContext,
  type ModuleRuntimeContextState,
} from '@/web-core/index.ts';
import { createApplicationManagementState } from '@/views/applicationManagementState.ts';

it('application management state selects first loaded application and creates records with alias as id', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, sortOrder: 10 } };
    },
  });
  const state = createApplicationManagementState(context, async () => true);

  state.handleListLoaded([{ id: 'platform', alias: 'platform', title: '平台', enabled: true }]);
  state.startCreate();
  state.draft.value.alias = '  sales  ';
  state.draft.value.title = '  销售应用  ';

  await state.save();

  assert.equal(state.selected.value?.id, 'sales');
  assert.equal(state.selected.value?.alias, 'sales');
  assert.equal(state.mode.value, 'view');
  assert.equal(state.reloadKey.value, 1);
  assert.deepEqual(calls[0], {
    id: 'sales',
    alias: 'sales',
    enabled: true,
    title: '销售应用',
  });
});

it('application management state keeps existing alias stable while editing title', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    update: async (id, record) => {
      calls.push({ id, record });
      return { record: { ...record, title: '平台配置' } };
    },
  });
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  state.startEdit();
  state.draft.value.alias = 'changed';
  state.draft.value.title = '平台配置';
  await state.save();

  assert.deepEqual(calls[0], {
    id: 'platform',
    record: {
      id: 'platform',
      alias: 'platform',
      title: '平台配置',
      enabled: true,
      version: 0,
    },
  });
  assert.equal(state.selected.value?.alias, 'platform');
});

it('application management state toggles enable state and refreshes selected record', async () => {
  const calls: string[] = [];
  const context = createContext({
    disable: async (id) => {
      calls.push(`disable:${id}`);
      return 1;
    },
    view: async (id) => {
      calls.push(`view:${id}`);
      return { id, alias: id, title: '平台', enabled: false, version: 1 };
    },
  });
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  await state.toggleEnabled();

  assert.deepEqual(calls, ['disable:platform', 'view:platform']);
  assert.equal(state.selected.value?.enabled, false);
  assert.equal(state.reloadKey.value, 1);
});

it('application management state runs standard action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const context = createContext({
    insert: async (record) => ({
      record: { ...record, sortOrder: 10 },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const state = createApplicationManagementState(context, async () => true, {
    actionResultReactionHandlers: {
      [platformActionResultReactionTypes.refreshList]: () => {
        handled.push(`reload:${state.reloadKey.value}`);
      },
    },
  });

  state.startCreate();
  state.draft.value.alias = 'sales';
  state.draft.value.title = '销售应用';

  await state.save();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.reloadKey.value, 1);
  assert.deepEqual(handled, ['reload:1']);
});

it('application management state refreshes selected draft from loaded records in view mode', () => {
  const context = createContext();
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  state.handleListLoaded([
    { id: 'platform', alias: 'platform', title: '平台配置', enabled: false, sortOrder: 20 },
  ]);

  assert.equal(state.selected.value?.title, '平台配置');
  assert.equal(state.selected.value?.enabled, false);
  assert.equal(state.draft.value.title, '平台配置');
  assert.equal(state.draft.value.enabled, false);
});

it('application management state does not overwrite editing draft when loaded records refresh', () => {
  const context = createContext();
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  state.startEdit();
  state.draft.value.title = '本地编辑';
  state.handleListLoaded([{ id: 'platform', alias: 'platform', title: '远端刷新', enabled: false }]);

  assert.equal(state.selected.value?.title, '远端刷新');
  assert.equal(state.draft.value.title, '本地编辑');
  assert.equal(state.draft.value.enabled, true);
});

it('application management state ignores duplicate save while saving', async () => {
  const calls: unknown[] = [];
  let releaseInsert: ((record: Application) => void) | undefined;
  const context = createContext({
    insert: async (record) => {
      calls.push(record);
      return new Promise((resolve) => {
        releaseInsert = () => resolve({ record: { ...record, sortOrder: 10 } });
      });
    },
  });
  const state = createApplicationManagementState(context, async () => true);

  state.startCreate();
  state.draft.value.alias = 'sales';
  state.draft.value.title = '销售应用';
  const firstSave = state.save();
  await Promise.resolve();
  const secondSave = state.save();

  assert.equal(calls.length, 1);

  releaseInsert?.({ id: 'sales', alias: 'sales', title: '销售应用', enabled: true });
  await Promise.all([firstSave, secondSave]);

  assert.equal(calls.length, 1);
  assert.equal(state.selected.value?.id, 'sales');
});

it('application management state cancels creation back to selected application', () => {
  const context = createContext();
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  state.startCreate();
  state.cancelEdit();

  assert.equal(state.selected.value?.id, 'platform');
  assert.equal(state.draft.value.id, 'platform');
  assert.equal(state.mode.value, 'view');
});

it('application management state respects delete confirmation result', async () => {
  const calls: string[] = [];
  const context = createContext({
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  let confirmed = false;
  const state = createApplicationManagementState(context, async () => confirmed);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  await state.removeSelected();

  assert.deepEqual(calls, []);
  assert.equal(state.selected.value?.id, 'platform');

  confirmed = true;
  await state.removeSelected();

  assert.deepEqual(calls, ['delete:platform']);
  assert.equal(state.selected.value, undefined);
  assert.equal(state.mode.value, 'create');
});

it('application management state records unhandled chain errors for platform fallback', async () => {
  const context = createContext({
    delete: async () => {
      throw new AppError('该应用下仍有字典类目，不能删除', {
        code: platformErrorCodes.resourceInUse,
        status: 409,
      });
    },
  });
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'app', alias: 'app', title: '测试应用', enabled: true, version: 0 });
  await state.removeSelected();

  assert.equal(state.actionError.value, '该应用下仍有字典类目，不能删除');
  assert.equal(state.selected.value?.id, 'app');
});

it('application management state lets business handler own matched action errors', async () => {
  const handled: string[] = [];
  const context = createContext({
    delete: async () => {
      throw new AppError('该应用下仍有字典类目，不能删除', {
        code: platformErrorCodes.resourceInUse,
        status: 409,
        details: { referencedResource: 'dictionaryCategory' },
      });
    },
  });
  const state = createApplicationManagementState(context, async () => true, {
    actionErrorHandlers: [
      {
        code: platformErrorCodes.resourceInUse,
        handle: (error, errorContext) => {
          handled.push(`${errorContext.actionCode}:${error.details?.referencedResource ?? ''}`);
        },
      },
    ],
  });

  state.handleSelect({ id: 'app', alias: 'app', title: '测试应用', enabled: true, version: 0 });
  await state.removeSelected();

  assert.deepEqual(handled, ['delete:dictionaryCategory']);
  assert.equal(state.actionError.value, undefined);
});

it('application management state does not enter create mode without create permission', async () => {
  const context = createContext({}, (actionCode) => actionCode !== 'create');
  const state = createApplicationManagementState(context, async () => true);

  state.handleListLoaded([]);

  assert.equal(state.canCreate.value, false);
  assert.equal(state.mode.value, 'view');

  state.startCreate();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.actionError.value, '当前用户无权新建应用');
});

it('application management state stays readonly after deleting last application without create permission', async () => {
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
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true, version: 0 });
  await state.removeSelected();

  assert.deepEqual(calls, ['delete:platform']);
  assert.equal(state.selected.value, undefined);
  assert.equal(state.mode.value, 'view');
});

it('application management state exposes action authorization flags', () => {
  const context = createContext({}, (actionCode) => actionCode === 'view');
  const state = createApplicationManagementState(context, async () => true);

  state.handleSelect({ id: 'platform', alias: 'platform', title: '平台', enabled: true });

  assert.equal(state.canCreate.value, false);
  assert.equal(state.canUpdate.value, false);
  assert.equal(state.canDelete.value, false);
  assert.equal(state.canEnable.value, false);
  assert.equal(state.canMutate.value, false);
});

function createContext(
  overrides: Partial<ModuleContext<Application>['crud']> = {},
  canAction: (actionCode: string) => boolean | undefined = () => true,
): ModuleContext<Application> {
  const crud: ModuleContext<Application>['crud'] = {
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
    moduleAlias: 'platform.application',
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
    can: canAction,
  };
}

function fakeRuntimeState(): ModuleRuntimeContextState {
  return {
    ready: Promise.resolve({
      moduleAlias: 'platform.application',
      capabilities: ['CRUD', 'ENABLE', 'SORT'],
      abilities: ['crud', 'enable', 'sort'],
      actions: [],
    }),
    load: async () => ({
      moduleAlias: 'platform.application',
      capabilities: ['CRUD', 'ENABLE', 'SORT'],
      abilities: ['crud', 'enable', 'sort'],
      actions: [],
    }),
    snapshot: () => undefined,
    error: () => undefined,
    hasAbility: () => undefined,
    action: () => undefined,
    can: () => undefined,
  };
}
