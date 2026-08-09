import { assert, it } from 'vitest';
import type { Position, PositionCategory } from '@/web-contracts/index.ts';
import { platformActionResultReactionTypes } from '@/platform-components/platformActionResultFeedback.ts';
import type { ModuleContext, ModuleRuntimeContextState } from '@/web-core/index.ts';
import {
  createPositionManagementState,
  emptyCategoryDraft,
  emptyPositionDraft,
  isValidCategory,
  isValidPosition,
  normalizeCategoryDraft,
  normalizePositionDraft,
  positionCategoryTitleOf,
  positionMatchesCategory,
  positionTitleOf,
} from '@/views/positionManagementState.ts';

it('position management state creates category-bound position drafts', () => {
  const draft = emptyPositionDraft('category-tech');

  assert.deepEqual(draft, {
    categoryId: 'category-tech',
    code: '',
    title: '',
    description: '',
    enabled: true,
  });
  assert.equal(isValidPosition(draft), false);
});

it('position management state trims position drafts and requires category', () => {
  const draft = normalizePositionDraft({
    id: 'pos-dev',
    categoryId: '  category-tech  ',
    code: '  DEV  ',
    title: '  开发工程师  ',
    description: '   ',
    enabled: true,
  });

  assert.deepEqual(draft, {
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
    description: undefined,
    enabled: true,
  });
  assert.equal(isValidPosition(draft), true);
  assert.equal(isValidPosition({ ...draft, categoryId: undefined }), false);
});

it('position management state matches positions only inside selected category', () => {
  assert.equal(
    positionMatchesCategory({ id: 'pos-dev', categoryId: 'category-tech' }, 'category-tech'),
    true,
  );
  assert.equal(
    positionMatchesCategory({ id: 'pos-dev', categoryId: 'category-ops' }, 'category-tech'),
    false,
  );
  assert.equal(positionMatchesCategory({ id: 'pos-dev', categoryId: 'category-tech' }, undefined), false);
});

it('position management state trims category drafts and clears blank parent id', () => {
  const draft = normalizeCategoryDraft({
    parentId: '   ',
    code: '  TECH  ',
    title: '  技术序列  ',
    description: '  研发岗位分类  ',
    enabled: true,
  });

  assert.deepEqual(draft, {
    parentId: undefined,
    code: 'TECH',
    title: '技术序列',
    description: '研发岗位分类',
    enabled: true,
  });
  assert.equal(isValidCategory(draft), true);
  assert.equal(isValidCategory({ ...draft, title: '' }), false);
});

it('position management state exposes stable fallback titles', () => {
  assert.equal(positionTitleOf({ code: 'DEV' }), 'DEV');
  assert.equal(positionTitleOf(undefined), '岗位详情');
  assert.equal(positionCategoryTitleOf({ code: 'TECH' }), 'TECH');
  assert.equal(positionCategoryTitleOf(undefined), '岗位分类');
  assert.deepEqual(emptyCategoryDraft('parent'), {
    parentId: 'parent',
    code: '',
    title: '',
    description: '',
    enabled: true,
  });
});

it('position management state loads positions inside selected category', async () => {
  const requests: unknown[] = [];
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    query: async (request) => {
      requests.push(request);
      return {
        records: [{ id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' }],
        total: 2,
        pageNum: 1,
        pageSize: 1,
        pages: 1,
        totalKnown: true,
      };
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([
    { id: 'category-tech', code: 'TECH', title: '技术序列' },
    { id: 'category-admin', code: 'ADMIN', title: '职能序列' },
  ]);
  await state.loadPositions();

  assert.equal(state.selectedCategory.value?.id, 'category-tech');
  assert.deepEqual(
    state.filteredPositions.value.map((record) => record.id),
    ['pos-dev'],
  );
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');
  assert.deepEqual(requests[0], {
    unpaged: true,
    conditions: [{ fieldName: 'categoryId', operator: 'EQ', values: ['category-tech'] }],
  });
});

it('position management state saves normalized positions with required category', async () => {
  const calls: unknown[] = [];
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, id: 'pos-dev' } };
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreatePosition();
  state.positionDraft.value.code = '  DEV  ';
  state.positionDraft.value.title = '  开发工程师  ';
  await state.savePosition();

  assert.deepEqual(calls[0], {
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
    description: undefined,
    enabled: true,
  });
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');
  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.positionReloadKey.value, 1);
});

it('position management state runs category action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const categoryContext = createContext<PositionCategory>('iam.position_category', {
    insert: async (record) => ({
      record: { ...record, id: 'category-child' },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true, {
    categoryActionResultReactionHandlers: {
      [platformActionResultReactionTypes.refreshList]: () => {
        handled.push(`categoryReload:${state.categoryReloadKey.value}`);
      },
    },
  });

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreateChildCategory();
  state.categoryDraft.value.code = 'DEV';
  state.categoryDraft.value.title = '研发';

  await state.saveCategory();

  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryReloadKey.value, 1);
  assert.deepEqual(handled, ['categoryReload:1']);
});

it('position management state runs position action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    insert: async (record) => ({
      record: { ...record, id: 'pos-dev' },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true, {
    positionActionResultReactionHandlers: {
      [platformActionResultReactionTypes.refreshList]: () => {
        handled.push(`positionReload:${state.positionReloadKey.value}`);
      },
    },
  });

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreatePosition();
  state.positionDraft.value.code = 'DEV';
  state.positionDraft.value.title = '开发工程师';

  await state.savePosition();

  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.positionReloadKey.value, 1);
  assert.deepEqual(handled, ['positionReload:1']);
});

it('position management state moves selected category when saved position changes category', async () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    update: async (_id, record) => ({ record: { ...record, id: 'pos-dev' } }),
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([
    { id: 'category-tech', code: 'TECH', title: '技术序列' },
    { id: 'category-admin', code: 'ADMIN', title: '职能序列' },
  ]);
  state.positions.value = [
    { id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' },
    { id: 'pos-test', categoryId: 'category-tech', code: 'TEST', title: '测试工程师' },
  ];
  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
  });
  state.startEditPosition();
  state.positionDraft.value.categoryId = 'category-admin';

  await state.savePosition();

  assert.equal(state.selectedCategory.value?.id, 'category-admin');
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');
  assert.equal(state.selectedPosition.value?.categoryId, 'category-admin');
  assert.deepEqual(
    state.filteredPositions.value.map((record) => record.id),
    ['pos-dev'],
  );
  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.positionReloadKey.value, 1);
});

it('position management state leaves position edit mode when switching category', () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([
    { id: 'category-tech', code: 'TECH', title: '技术序列' },
    { id: 'category-admin', code: 'ADMIN', title: '职能序列' },
  ]);
  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
  });
  state.startEditPosition();
  state.positionDraft.value.title = '待保存岗位';

  state.handleSelectCategory({ id: 'category-admin', code: 'ADMIN', title: '职能序列' });

  assert.equal(state.selectedCategory.value?.id, 'category-admin');
  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.selectedPosition.value, undefined);
  assert.deepEqual(state.positionDraft.value, emptyPositionDraft('category-admin'));
  assert.equal(state.positionError.value, undefined);
});

it('position management state cancels category creation back to selected category', () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);

  state.startCreateRootCategory();

  assert.equal(state.categoryMode.value, 'create-root');
  assert.equal(state.selectedCategory.value?.id, 'category-tech');
  assert.equal(state.categoryDraft.value.parentId, undefined);

  state.categoryDraft.value.title = '临时根分类';
  state.cancelCategoryEdit();

  assert.equal(state.selectedCategory.value?.id, 'category-tech');
  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryDraft.value.title, '技术序列');

  state.startCreateChildCategory();

  assert.equal(state.categoryMode.value, 'create-child');
  assert.equal(state.selectedCategory.value?.id, 'category-tech');
  assert.equal(state.categoryDraft.value.parentId, 'category-tech');

  state.categoryDraft.value.title = '临时子分类';
  state.cancelCategoryEdit();

  assert.equal(state.selectedCategory.value?.id, 'category-tech');
  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryDraft.value.title, '技术序列');
});

it('position management state keeps category create draft when categories reload', () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);

  state.startCreateRootCategory();
  state.categoryDraft.value.title = '未保存分类';
  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '刷新后的技术序列' }]);

  assert.equal(state.categoryMode.value, 'create-root');
  assert.equal(state.selectedCategory.value?.title, '刷新后的技术序列');
  assert.equal(state.categoryDraft.value.title, '未保存分类');
});

it('position management state keeps category editor closed after deleting category', async () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category', {
    delete: async () => 1,
  });
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  await state.deleteCategory();

  assert.equal(state.selectedCategory.value, undefined);
  assert.equal(state.categoryMode.value, 'view');

  state.handleCategoriesLoaded([]);

  assert.equal(state.selectedCategory.value, undefined);
  assert.equal(state.categoryMode.value, 'view');
});

it('position management state cancels creating a position back to empty view', () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreatePosition();
  state.positionDraft.value.code = 'DEV';
  state.cancelPositionEdit();

  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.selectedPosition.value, undefined);
  assert.deepEqual(state.positionDraft.value, emptyPositionDraft('category-tech'));
});

it('position management state cancels creating a position back to selected position', () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.positions.value = [{ id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' }];
  state.syncSelectedPosition();

  state.startCreatePosition();

  assert.equal(state.positionMode.value, 'create');
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');
  assert.equal(state.positionDraft.value.categoryId, 'category-tech');

  state.positionDraft.value.title = '临时岗位';
  state.cancelPositionEdit();

  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');
  assert.equal(state.positionDraft.value.title, '开发工程师');
});

it('position management state exposes category toggle authorization', async () => {
  const calls: string[] = [];
  const categoryContext = createContext<PositionCategory>(
    'iam.position_category',
    {
      disable: async (id) => {
        calls.push(`disable:${id}`);
        return 1;
      },
    },
    (actionCode) => actionCode !== 'disable',
  );
  const positionContext = createContext<Position>('iam.position');
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);

  assert.equal(state.canToggleCategory.value, false);
  await state.toggleCategory();

  assert.equal(state.categoryError.value, '当前用户无权变更岗位分类启停状态');
  assert.deepEqual(calls, []);
});

it('position management state respects delete confirmation result', async () => {
  const calls: string[] = [];
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  let confirmed = false;
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => confirmed);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.selectPosition({ id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' });
  await state.deletePosition();

  assert.deepEqual(calls, []);
  assert.equal(state.selectedPosition.value?.id, 'pos-dev');

  confirmed = true;
  await state.deletePosition();

  assert.deepEqual(calls, ['delete:pos-dev']);
  assert.equal(state.selectedPosition.value, undefined);
  assert.equal(state.positionMode.value, 'create');
});

it('position management state keeps delete fallback creation without selecting reload rows', async () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    delete: async () => 1,
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.selectPosition({ id: 'pos-deleted', categoryId: 'category-tech', code: 'DEL', title: '待删除岗位' });

  await state.deletePosition();
  state.positions.value = [{ id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' }];
  state.syncSelectedPosition();
  state.cancelPositionEdit();

  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.selectedPosition.value, undefined);
  assert.deepEqual(state.positionDraft.value, emptyPositionDraft('category-tech'));
});

it('position management state notifies delete failures globally', async () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category', {
    delete: async () => {
      throw new Error('position category is referenced by positions: category-tech');
    },
  });
  const positionContext = createContext<Position>('iam.position', {
    delete: async () => {
      throw new Error('position is referenced by employees: pos-dev');
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  await state.deleteCategory();
  assert.equal(state.categoryError.value, 'position category is referenced by positions: category-tech');

  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
  });
  await state.deletePosition();
  assert.equal(state.positionError.value, 'position is referenced by employees: pos-dev');
});

it('position management state notifies action failures through unified feedback', async () => {
  const categoryContext = createContext<PositionCategory>('iam.position_category');
  const positionContext = createContext<Position>('iam.position', {
    insert: async () => {
      throw new Error('position save failed');
    },
    enable: async () => {
      throw new Error('position enable failed');
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreatePosition();
  state.positionDraft.value.code = 'DEV';
  state.positionDraft.value.title = '开发工程师';
  await state.savePosition();

  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
    enabled: false,
  });
  await state.togglePosition();

  assert.equal(state.positionError.value, 'position enable failed');
});

it('position management state stays readonly without create permissions after empty states', async () => {
  const categoryContext = createContext<PositionCategory>(
    'iam.position_category',
    {
      delete: async () => 1,
    },
    (actionCode) => actionCode !== 'create' && actionCode !== 'position_create',
  );
  const positionContext = createContext<Position>('iam.position', {
    delete: async () => 1,
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([]);
  assert.equal(state.categoryMode.value, 'view');

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  await state.deleteCategory();
  assert.equal(state.categoryMode.value, 'view');

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
  });
  await state.deletePosition();
  assert.equal(state.positionMode.value, 'view');
});

it('position management state rejects position actions without contributed permissions', async () => {
  const calls: string[] = [];
  const categoryContext = createContext<PositionCategory>(
    'iam.position_category',
    {},
    (actionCode) => !actionCode.startsWith('position_'),
  );
  const positionContext = createContext<Position>('iam.position', {
    insert: async (record) => {
      calls.push(`insert:${record.code}`);
      return { record };
    },
    update: async (id, record) => {
      calls.push(`update:${id}:${record.code}`);
      return { record };
    },
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
    enable: async (id) => {
      calls.push(`enable:${id}`);
      return 1;
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  state.startCreatePosition();

  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.positionError.value, '当前用户无权新增岗位');

  state.selectPosition({
    id: 'pos-dev',
    categoryId: 'category-tech',
    code: 'DEV',
    title: '开发工程师',
  });
  state.startEditPosition();
  assert.equal(state.positionMode.value, 'view');
  assert.equal(state.positionError.value, '当前用户无权编辑岗位');

  await state.deletePosition();
  assert.equal(state.positionError.value, '当前用户无权删除岗位');

  await state.togglePosition();
  assert.equal(state.positionError.value, '当前用户无权变更岗位启停状态');
  assert.deepEqual(calls, []);
});

it('position management state does not query positions without contributed query permission', async () => {
  const requests: unknown[] = [];
  const categoryContext = createContext<PositionCategory>(
    'iam.position_category',
    {},
    (actionCode) => actionCode !== 'position_query',
  );
  const positionContext = createContext<Position>('iam.position', {
    query: async (request) => {
      requests.push(request);
      return {
        records: [{ id: 'pos-dev', categoryId: 'category-tech', code: 'DEV', title: '开发工程师' }],
        total: 1,
        pageNum: 1,
        pageSize: 1,
        pages: 1,
        totalKnown: true,
      };
    },
  });
  const state = createPositionManagementState(categoryContext, positionContext.crud, async () => true);

  state.handleCategoriesLoaded([{ id: 'category-tech', code: 'TECH', title: '技术序列' }]);
  await state.loadPositions();

  assert.equal(state.canQueryPosition.value, false);
  assert.deepEqual(requests, []);
  assert.deepEqual(state.positions.value, []);
  assert.equal(state.positionError.value, undefined);
});

function createContext<TRecord>(
  moduleAlias: string,
  overrides: Partial<ModuleContext<TRecord>['crud']> = {},
  can: (actionCode: string) => boolean = () => true,
): ModuleContext<TRecord> {
  const crud: ModuleContext<TRecord>['crud'] = {
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id }) as TRecord,
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
    moduleAlias,
    crud,
    runtime: fakeRuntimeState(moduleAlias),
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
    can,
  };
}

function fakeRuntimeState(moduleAlias: string): ModuleRuntimeContextState {
  return {
    ready: Promise.resolve({
      moduleAlias,
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    load: async () => ({
      moduleAlias,
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
