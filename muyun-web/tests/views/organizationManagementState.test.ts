import { assert, it } from 'vitest';
import type { Organization } from '@/web-contracts/index.ts';
import { platformActionResultReactionTypes } from '@/platform-components/platformActionResultFeedback.ts';
import type { ModuleContext, ModuleRuntimeContextState } from '@/web-core/index.ts';
import { createOrganizationManagementState } from '@/views/organizationManagementState.ts';

it('organization management state selects first loaded organization and creates child records', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, id: 'org-child' } };
    },
  });
  const state = createOrganizationManagementState(context, async () => true);

  state.handleTreeLoaded([{ id: 'org-root', code: 'ROOT', title: '总部', enabled: true }]);
  state.startCreateChild();
  state.draft.value.title = '  华东区  ';
  state.draft.value.code = '  EAST  ';

  await state.save();

  assert.equal(state.selected.value?.id, 'org-child');
  assert.equal(state.mode.value, 'view');
  assert.equal(state.reloadKey.value, 1);
  assert.deepEqual(calls[0], {
    parentId: 'org-root',
    enabled: true,
    title: '华东区',
    code: 'EAST',
  });
});

it('organization management state updates existing records and refreshes enable state', async () => {
  const calls: string[] = [];
  const context = createContext({
    update: async (id, record) => {
      calls.push(`update:${id}:${record.title}`);
      return { record: { ...record, title: '总部修订' } };
    },
    disable: async (id) => {
      calls.push(`disable:${id}`);
      return 1;
    },
    view: async (id) => {
      calls.push(`view:${id}`);
      return { id, code: 'ROOT', title: '总部修订', enabled: false };
    },
  });
  const state = createOrganizationManagementState(context, async () => true);

  state.handleSelect({ id: 'org-root', code: 'ROOT', title: '总部', enabled: true });
  state.startEdit();
  state.draft.value.title = '总部修订';
  await state.save();
  await state.toggleEnabled();

  assert.deepEqual(calls, ['update:org-root:总部修订', 'disable:org-root', 'view:org-root']);
  assert.equal(state.selected.value?.enabled, false);
  assert.equal(state.reloadKey.value, 2);
});

it('organization management state runs standard action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const context = createContext({
    insert: async (record) => ({
      record: { ...record, id: 'org-child' },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const state = createOrganizationManagementState(context, async () => true, {
    actionResultReactionHandlers: {
      [platformActionResultReactionTypes.refreshList]: () => {
        handled.push(`reload:${state.reloadKey.value}`);
      },
    },
  });

  state.handleTreeLoaded([{ id: 'org-root', code: 'ROOT', title: '总部', enabled: true }]);
  state.startCreateChild();
  state.draft.value.title = '华东区';
  state.draft.value.code = 'EAST';

  await state.save();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.reloadKey.value, 1);
  assert.deepEqual(handled, ['reload:1']);
});

it('organization management state clears selection through standard delete reactions', async () => {
  const context = createContext({
    delete: async () => ({
      count: 1,
      reactions: [
        { type: platformActionResultReactionTypes.clearSelection },
        { type: platformActionResultReactionTypes.refreshList },
      ],
    }),
  });
  const state = createOrganizationManagementState(context, async () => true);

  state.handleSelect({ id: 'org-root', code: 'ROOT', title: '总部', enabled: true });
  await state.removeSelected();

  assert.equal(state.selected.value, undefined);
  assert.equal(state.draft.value.title, '');
  assert.equal(state.mode.value, 'create');
  assert.equal(state.reloadKey.value, 1);
});

it('organization management state trims parent id and clears blank parent id', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    update: async (_id, record) => {
      calls.push(record);
      return { record: { ...record, id: 'org-east' } };
    },
  });
  const state = createOrganizationManagementState(context, async () => true);

  state.handleSelect({ id: 'org-east', code: 'EAST', title: '华东', parentId: 'org-root', enabled: true });
  state.startEdit();
  state.draft.value.parentId = '   ';
  await state.save();

  assert.equal((calls[0] as Organization).parentId, undefined);

  state.startEdit();
  state.draft.value.parentId = '  org-root  ';
  await state.save();

  assert.equal((calls[1] as Organization).parentId, 'org-root');
});

it('organization management state cancels root creation back to selected organization', () => {
  const state = createOrganizationManagementState(createContext(), async () => true);

  state.handleSelect({ id: 'org-root', code: 'ROOT', title: '总部', enabled: true });
  state.startCreateRoot();
  state.draft.value.title = '临时机构';
  state.cancelEdit();

  assert.equal(state.selected.value?.id, 'org-root');
  assert.equal(state.mode.value, 'view');
  assert.equal(state.draft.value.title, '总部');
});

it('organization management state respects delete confirmation result', async () => {
  const calls: string[] = [];
  const context = createContext({
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  let confirmed = false;
  const state = createOrganizationManagementState(context, async () => confirmed);

  state.handleSelect({ id: 'org-root', code: 'ROOT', title: '总部', enabled: true });
  await state.removeSelected();

  assert.deepEqual(calls, []);
  assert.equal(state.selected.value?.id, 'org-root');

  confirmed = true;
  await state.removeSelected();

  assert.deepEqual(calls, ['delete:org-root']);
  assert.equal(state.selected.value, undefined);
  assert.equal(state.mode.value, 'create');
});

it('organization management state exposes action authorization flags', () => {
  const context = createContext({}, (actionCode) => actionCode === 'view');
  const state = createOrganizationManagementState(context, async () => true);

  state.handleSelect({ id: 'org-root', code: 'ROOT', title: '总部', enabled: true });

  assert.equal(state.canCreate.value, false);
  assert.equal(state.canUpdate.value, false);
  assert.equal(state.canDelete.value, false);
  assert.equal(state.canEnable.value, false);
  assert.equal(state.canMutate.value, false);
});

function createContext(
  overrides: Partial<ModuleContext<Organization>['crud']> = {},
  canAction: (actionCode: string) => boolean | undefined = () => true,
): ModuleContext<Organization> {
  const crud: ModuleContext<Organization>['crud'] = {
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id, code: 'ROOT', title: '总部', enabled: true }),
    insert: async (record) => ({ record: { ...record, id: 'org-new' } }),
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
    moduleAlias: 'iam.organization',
    crud,
    runtime: fakeRuntimeState(),
    abilities: {
      crud: () => crud,
      tree: () => tree,
      enable: () => enable,
      tryCrud: () => crud,
      tryTree: () => tree,
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
      moduleAlias: 'iam.organization',
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    load: async () => ({
      moduleAlias: 'iam.organization',
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    snapshot: () => undefined,
    error: () => undefined,
    hasAbility: () => undefined,
    action: () => undefined,
    can: () => undefined,
  };
}
