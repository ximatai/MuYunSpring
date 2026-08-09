import { assert, it } from 'vitest';
import type { Department } from '@/web-contracts/index.ts';
import type { ModuleContext, ModuleRuntimeContextState } from '@/web-core/index.ts';
import {
  createDepartmentManagementState,
  departmentTitleOf,
  emptyDepartmentDraft,
  isValidDepartment,
  normalizeDepartmentDraft,
  organizationTitleOf,
} from '@/views/departmentManagementState.ts';

it('department management state creates organization-bound drafts', () => {
  const draft = emptyDepartmentDraft('org-root');

  assert.deepEqual(draft, {
    organizationId: 'org-root',
    parentId: undefined,
    code: '',
    title: '',
    enabled: true,
  });
  assert.equal(isValidDepartment(draft), false);
});

it('department management state normalizes required fields and parent id', () => {
  const draft = normalizeDepartmentDraft(
    {
      organizationId: '   ',
      parentId: '  dept-parent  ',
      code: '  FIN  ',
      title: '  财务部  ',
      enabled: true,
    },
    'org-root',
  );

  assert.deepEqual(draft, {
    organizationId: 'org-root',
    parentId: 'dept-parent',
    code: 'FIN',
    title: '财务部',
    enabled: true,
  });
  assert.equal(isValidDepartment(draft), true);
  assert.equal(isValidDepartment({ ...draft, organizationId: undefined }), false);
});

it('department management state resets department selection when organization changes', () => {
  const state = createDepartmentManagementState(createContext(), async () => true);

  state.handleOrganizationsLoaded([
    { id: 'org-root', code: 'ROOT', title: '总部' },
    { id: 'org-east', code: 'EAST', title: '华东机构' },
  ]);
  state.handleDepartmentsLoaded([
    { id: 'dept-fin', organizationId: 'org-root', code: 'FIN', title: '财务部' },
  ]);

  state.selectOrganization({ id: 'org-east', code: 'EAST', title: '华东机构' });

  assert.equal(state.selectedOrganization.value?.id, 'org-east');
  assert.equal(state.selectedDepartment.value, undefined);
  assert.deepEqual(state.departments.value, []);
  assert.equal(state.draft.value.organizationId, 'org-east');
  assert.equal(state.departmentReloadKey.value, 2);
});

it('department management state saves root and child departments inside selected organization', async () => {
  const calls: unknown[] = [];
  const context = createContext({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, id: `dept-${calls.length}` } };
    },
  });
  const state = createDepartmentManagementState(context, async () => true);

  state.handleOrganizationsLoaded([{ id: 'org-root', code: 'ROOT', title: '总部' }]);
  state.startCreateRoot();
  state.draft.value.code = '  FIN  ';
  state.draft.value.title = '  财务部  ';
  await state.save();

  state.startCreateChild(state.selectedDepartment.value);
  state.draft.value.code = '  AP  ';
  state.draft.value.title = '  应付组  ';
  await state.save();

  assert.deepEqual(calls[0], {
    organizationId: 'org-root',
    parentId: undefined,
    code: 'FIN',
    title: '财务部',
    enabled: true,
  });
  assert.deepEqual(calls[1], {
    organizationId: 'org-root',
    parentId: 'dept-1',
    code: 'AP',
    title: '应付组',
    enabled: true,
  });
  assert.equal(state.selectedDepartment.value?.id, 'dept-2');
  assert.equal(state.mode.value, 'view');
  assert.equal(state.departmentReloadKey.value, 3);
});

it('department management state cancels child creation back to selected parent', () => {
  const state = createDepartmentManagementState(createContext(), async () => true);

  state.handleOrganizationsLoaded([{ id: 'org-root', code: 'ROOT', title: '总部' }]);
  state.handleDepartmentsLoaded([
    { id: 'dept-fin', organizationId: 'org-root', code: 'FIN', title: '财务部' },
  ]);

  state.startCreateChild(state.selectedDepartment.value);

  assert.equal(state.mode.value, 'create-child');
  assert.equal(state.selectedDepartment.value?.id, 'dept-fin');
  assert.equal(state.draft.value.parentId, 'dept-fin');
  assert.equal(state.draft.value.organizationId, 'org-root');

  state.draft.value.title = '临时下级';
  state.cancelEdit();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.selectedDepartment.value?.id, 'dept-fin');
  assert.equal(state.draft.value.title, '财务部');
});

it('department management state cancels root creation back to selected department', () => {
  const state = createDepartmentManagementState(createContext(), async () => true);

  state.handleOrganizationsLoaded([{ id: 'org-root', code: 'ROOT', title: '总部' }]);
  state.handleDepartmentsLoaded([
    { id: 'dept-fin', organizationId: 'org-root', code: 'FIN', title: '财务部' },
  ]);

  state.startCreateRoot();

  assert.equal(state.mode.value, 'create-root');
  assert.equal(state.selectedDepartment.value?.id, 'dept-fin');
  assert.equal(state.draft.value.parentId, undefined);
  assert.equal(state.draft.value.organizationId, 'org-root');

  state.draft.value.title = '临时根部门';
  state.cancelEdit();

  assert.equal(state.mode.value, 'view');
  assert.equal(state.selectedDepartment.value?.id, 'dept-fin');
  assert.equal(state.draft.value.title, '财务部');
});

it('department management state keeps edit draft when departments reload', () => {
  const state = createDepartmentManagementState(createContext(), async () => true);

  state.handleOrganizationsLoaded([{ id: 'org-root', code: 'ROOT', title: '总部' }]);
  state.handleDepartmentsLoaded([
    { id: 'dept-fin', organizationId: 'org-root', code: 'FIN', title: '财务部' },
  ]);

  state.startEdit();
  state.draft.value.title = '未保存部门';
  state.handleDepartmentsLoaded([
    { id: 'dept-fin', organizationId: 'org-root', code: 'FIN', title: '刷新后的财务部' },
  ]);

  assert.equal(state.mode.value, 'edit');
  assert.equal(state.selectedDepartment.value?.title, '刷新后的财务部');
  assert.equal(state.draft.value.title, '未保存部门');
});

it('department management state exposes stable fallback titles', () => {
  assert.equal(organizationTitleOf({ code: 'ROOT' }), 'ROOT');
  assert.equal(organizationTitleOf(undefined), '机构');
  assert.equal(departmentTitleOf({ code: 'FIN' }), 'FIN');
  assert.equal(departmentTitleOf(undefined), '部门详情');
});

function createContext(
  overrides: Partial<ModuleContext<Department>['crud']> = {},
  canAction: (actionCode: string) => boolean | undefined = () => true,
): ModuleContext<Department> {
  const crud: ModuleContext<Department>['crud'] = {
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id, organizationId: 'org-root', code: 'FIN', title: '财务部', enabled: true }),
    insert: async (record) => ({ record: { ...record, id: 'dept-new' } }),
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
    moduleAlias: 'iam.department',
    http: {
      request: async () => {
        throw new Error('unexpected http request in state test');
      },
    },
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
      moduleAlias: 'iam.department',
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    load: async () => ({
      moduleAlias: 'iam.department',
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
