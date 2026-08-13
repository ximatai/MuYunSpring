import { assert, it } from 'vitest';
import type { DictionaryCategory, DictionaryItem } from '@/web-contracts/index.ts';
import { platformActionResultReactionTypes } from '@/platform-components/platformActionResultFeedback.ts';
import {
  AppError,
  platformErrorCodes,
  type ModuleContext,
  type ModuleRuntimeContextState,
  type StaticModuleTreeClient,
} from '@/web-core/index.ts';
import {
  createDictionaryManagementState,
  dictionaryCategoryKindTitle,
  dictionaryCategoryTitleOf,
  dictionaryItemTitleOf,
  emptyDictionaryCategoryDraft,
  emptyDictionaryItemDraft,
  isDictionaryCategory,
  isFolderCategory,
  isValidDictionaryCategory,
  isValidDictionaryItem,
  normalizeDictionaryCategoryDraft,
  normalizeDictionaryItemDraft,
} from '@/views/dictionaryManagementState.ts';

it('dictionary management state normalizes category and item drafts', () => {
  const category = normalizeDictionaryCategoryDraft({
    applicationAlias: '  platform  ',
    alias: '  status  ',
    categoryKind: 'folder',
    parentId: '   ',
    title: '  状态类  ',
    enabled: true,
  });
  const item = normalizeDictionaryItemDraft(
    {
      code: '  enabled  ',
      title: '  启用  ',
      parentId: '   ',
      enabled: true,
    },
    { id: 'category-status', alias: 'status' },
  );

  assert.deepEqual(category, {
    applicationAlias: 'platform',
    alias: 'status',
    categoryKind: 'FOLDER',
    parentId: undefined,
    title: '状态类',
    enabled: true,
  });
  assert.equal(isValidDictionaryCategory(category), true);
  assert.equal(isFolderCategory(category), true);
  assert.equal(isDictionaryCategory(category), false);
  assert.equal(dictionaryCategoryKindTitle(category), '目录');
  assert.deepEqual(item, {
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: 'enabled',
    title: '启用',
    parentId: undefined,
    enabled: true,
  });
  assert.equal(isValidDictionaryItem(item), true);
});

it('dictionary management state loads items under selected dictionary category', async () => {
  const categoryContext = createContext();
  const itemRequests: string[] = [];
  const itemClient = createItemClient({
    treeFlat: async () => {
      itemRequests.push('treeFlat');
      return {
        records: [{ id: 'item-enabled', categoryId: 'category-status', code: 'enabled', title: '启用' }],
      };
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  await state.loadItems();

  assert.equal(state.selectedCategory.value?.id, 'category-status');
  assert.equal(state.selectedCategoryIsDictionary.value, true);
  assert.deepEqual(itemRequests, ['treeFlat']);
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemDraft.value.code, 'enabled');
});

it('dictionary management state saves category-bound dictionary items', async () => {
  const calls: unknown[] = [];
  const categoryContext = createContext();
  const itemClient = createItemClient({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, id: 'item-enabled' } };
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.startCreateItem();
  state.itemDraft.value.code = '  enabled  ';
  state.itemDraft.value.title = '  启用  ';
  await state.saveItem();

  assert.deepEqual(calls[0], {
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: 'enabled',
    title: '启用',
    parentId: undefined,
    enabled: true,
  });
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.itemReloadKey.value, 1);
});

it('dictionary management state runs category action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const categoryContext = createContext();
  const categoryClient = createCategoryClient({
    insert: async (record) => ({
      record: { ...record, id: 'category-status' },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => categoryClient,
    () => createItemClient(),
    () => 'platform',
    async () => true,
    {
      categoryActionResultReactionHandlers: {
        [platformActionResultReactionTypes.refreshList]: () => {
          handled.push(`categoryReload:${state.categoryReloadKey.value}`);
        },
      },
    },
  );

  state.startCreateRootCategory();
  state.categoryDraft.value.alias = 'status';
  state.categoryDraft.value.title = '状态字典';
  await state.saveCategory();

  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryReloadKey.value, 1);
  assert.deepEqual(handled, ['categoryReload:1']);
});

it('dictionary management state runs item action reactions before custom handlers', async () => {
  const handled: string[] = [];
  const categoryContext = createContext();
  const itemClient = createItemClient({
    insert: async (record) => ({
      record: { ...record, id: 'item-enabled' },
      reactions: [{ type: platformActionResultReactionTypes.refreshList }],
    }),
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
    {
      itemActionResultReactionHandlers: {
        [platformActionResultReactionTypes.refreshList]: () => {
          handled.push(`itemReload:${state.itemReloadKey.value}`);
        },
      },
    },
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.startCreateItem();
  state.itemDraft.value.code = 'enabled';
  state.itemDraft.value.title = '启用';
  await state.saveItem();

  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.itemReloadKey.value, 1);
  assert.deepEqual(handled, ['itemReload:1']);
});

it('dictionary management state creates child dictionary items under selected parent', () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.startCreateChildItem({
    id: 'item-enabled',
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: 'enabled',
    title: '启用',
  });

  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemMode.value, 'create');
  assert.equal(state.itemDraft.value.categoryId, 'category-status');
  assert.equal(state.itemDraft.value.parentId, 'item-enabled');
});

it('dictionary management state cancels item creation back to selected item', () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.handleItemsLoaded([
    {
      id: 'item-enabled',
      categoryId: 'category-status',
      categoryAlias: 'status',
      code: 'enabled',
      title: '启用',
    },
  ]);

  state.startCreateItem();

  assert.equal(state.itemMode.value, 'create');
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemDraft.value.parentId, undefined);

  state.itemDraft.value.title = '临时字典项';
  state.cancelItemEdit();

  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemDraft.value.title, '启用');

  state.startCreateChildItem(state.selectedItem.value!);

  assert.equal(state.itemMode.value, 'create');
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemDraft.value.parentId, 'item-enabled');

  state.itemDraft.value.title = '临时下级字典项';
  state.cancelItemEdit();

  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.selectedItem.value?.id, 'item-enabled');
  assert.equal(state.itemDraft.value.title, '启用');
});

it('dictionary management state keeps delete fallback creation without selecting reload rows', async () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient({ delete: async () => 1 }),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.selectItem({
    id: 'item-deleted',
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: 'deleted',
    title: '待删除',
  });

  await state.deleteItem();
  state.handleItemsLoaded([
    {
      id: 'item-enabled',
      categoryId: 'category-status',
      categoryAlias: 'status',
      code: 'enabled',
      title: '启用',
    },
  ]);
  state.cancelItemEdit();

  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.selectedItem.value, undefined);
  assert.equal(state.itemDraft.value.title, '');
});

it('dictionary management state sends unexpected item save failures to global feedback', async () => {
  const categoryContext = createContext();
  const itemClient = createItemClient({
    insert: async () => {
      throw new AppError('Internal server error', {
        code: platformErrorCodes.internalError,
        status: 500,
      });
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.startCreateItem();
  state.itemDraft.value.code = 'enabled';
  state.itemDraft.value.title = '启用';
  await state.saveItem();

  assert.equal(state.itemError.value, undefined);
  assert.equal(state.itemMode.value, 'create');
  assert.equal(state.itemReloadKey.value, 0);
});

it('dictionary management state sends unexpected item load failures to global feedback', async () => {
  const categoryContext = createContext();
  const itemClient = createItemClient({
    treeFlat: async () => {
      throw new AppError('Internal server error', {
        code: platformErrorCodes.internalError,
        status: 500,
      });
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  await state.loadItems();

  assert.equal(state.itemError.value, undefined);
  assert.equal(state.itemLoading.value, false);
  assert.deepEqual(state.items.value, []);
});

it('dictionary management state does not load item tree without tree permission', async () => {
  const calls: string[] = [];
  const categoryContext = createContext((actionCode) => actionCode !== 'item_tree');
  const itemClient = createItemClient({
    treeFlat: async () => {
      calls.push('treeFlat');
      return { records: [] };
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  await state.loadItems();

  assert.equal(state.canQueryItem.value, true);
  assert.equal(state.canTreeItem.value, false);
  assert.deepEqual(calls, []);
  assert.deepEqual(state.items.value, []);
});

it('dictionary management state rejects item actions without contributed permissions', async () => {
  const calls: string[] = [];
  const categoryContext = createContext((actionCode) => !actionCode.startsWith('item_'));
  const itemClient = createItemClient({
    insert: async (record) => {
      calls.push(`insert:${record.code}`);
      return { record };
    },
    delete: async (id) => {
      calls.push(`delete:${id}`);
      return 1;
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => createCategoryClient(),
    () => itemClient,
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
    },
  ]);
  state.startCreateItem();

  assert.equal(state.itemMode.value, 'view');
  assert.equal(state.itemError.value, '当前用户无权新增字典项');

  state.selectItem({
    id: 'item-enabled',
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: 'enabled',
    title: '启用',
  });
  await state.deleteItem();

  assert.equal(state.itemError.value, '当前用户无权删除字典项');
  assert.deepEqual(calls, []);
});

it('dictionary management state exposes stable fallback labels', () => {
  assert.equal(dictionaryCategoryTitleOf({ alias: 'status' }), 'status');
  assert.equal(dictionaryCategoryTitleOf(undefined), '字典类目');
  assert.equal(dictionaryItemTitleOf({ code: 'enabled' }), 'enabled');
  assert.equal(dictionaryItemTitleOf(undefined), '字典项');
  assert.deepEqual(emptyDictionaryCategoryDraft('parent'), {
    applicationAlias: 'platform',
    alias: '',
    categoryKind: 'DICTIONARY',
    parentId: 'parent',
    title: '',
    enabled: true,
  });
  assert.deepEqual(emptyDictionaryItemDraft({ id: 'category-status', alias: 'status' }), {
    categoryId: 'category-status',
    categoryAlias: 'status',
    code: '',
    title: '',
    enabled: true,
  });
});

it('dictionary management state creates categories inside current application scope', async () => {
  let applicationAlias = 'crm';
  const calls: unknown[] = [];
  const categoryContext = createContext();
  const categoryClient = createCategoryClient({
    insert: async (record) => {
      calls.push(record);
      return { record: { ...record, id: 'category-status' } };
    },
  });
  const state = createDictionaryManagementState(
    categoryContext,
    () => categoryClient,
    () => createItemClient(),
    () => applicationAlias,
    async () => true,
  );

  state.startCreateRootCategory();
  state.categoryDraft.value.alias = '  customer_status  ';
  state.categoryDraft.value.title = '  客户状态  ';
  await state.saveCategory();

  assert.deepEqual(calls[0], {
    applicationAlias: 'crm',
    alias: 'customer_status',
    categoryKind: 'DICTIONARY',
    parentId: undefined,
    title: '客户状态',
    enabled: true,
  });

  applicationAlias = 'sales';
  state.resetForApplication();

  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.selectedCategory.value, undefined);
  assert.deepEqual(state.categories.value, []);

  state.startCreateRootCategory();

  assert.equal(state.categoryDraft.value.applicationAlias, 'sales');
  assert.equal(state.selectedCategory.value, undefined);
});

it('dictionary management category editor opens only for explicit edit intents', () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([]);
  assert.equal(state.categoryMode.value, 'view');

  state.startCreateRootCategory();
  assert.equal(state.categoryMode.value, 'create-root');

  state.cancelCategoryEdit();
  assert.equal(state.categoryMode.value, 'view');
});

it('dictionary management state cancels category creation back to selected category', () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
      enabled: true,
    },
  ]);

  state.startCreateRootCategory();

  assert.equal(state.categoryMode.value, 'create-root');
  assert.equal(state.selectedCategory.value?.id, 'category-status');
  assert.equal(state.categoryDraft.value.parentId, undefined);
  assert.equal(state.categoryDraft.value.applicationAlias, 'platform');

  state.categoryDraft.value.title = '临时根类目';
  state.cancelCategoryEdit();

  assert.equal(state.selectedCategory.value?.id, 'category-status');
  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryDraft.value.title, '状态字典');

  state.startCreateChildCategory();

  assert.equal(state.categoryMode.value, 'create-child');
  assert.equal(state.selectedCategory.value?.id, 'category-status');
  assert.equal(state.categoryDraft.value.parentId, 'category-status');
  assert.equal(state.categoryDraft.value.applicationAlias, 'platform');

  state.categoryDraft.value.title = '临时子类目';
  state.cancelCategoryEdit();

  assert.equal(state.selectedCategory.value?.id, 'category-status');
  assert.equal(state.categoryMode.value, 'view');
  assert.equal(state.categoryDraft.value.title, '状态字典');
});

it('dictionary management state keeps category create draft when categories reload', () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient(),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
      enabled: true,
    },
  ]);

  state.startCreateRootCategory();
  state.categoryDraft.value.title = '未保存类目';
  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '刷新后的状态字典',
      enabled: true,
    },
  ]);

  assert.equal(state.categoryMode.value, 'create-root');
  assert.equal(state.selectedCategory.value?.title, '刷新后的状态字典');
  assert.equal(state.categoryDraft.value.title, '未保存类目');
});

it('dictionary management state keeps category editor closed after deleting category', async () => {
  const state = createDictionaryManagementState(
    createContext(),
    () => createCategoryClient({ delete: async () => 1 }),
    () => createItemClient(),
    () => 'platform',
    async () => true,
  );

  state.handleCategoriesLoaded([
    {
      id: 'category-status',
      applicationAlias: 'platform',
      alias: 'status',
      categoryKind: 'DICTIONARY',
      title: '状态字典',
      enabled: true,
    },
  ]);
  await state.deleteCategory();

  assert.equal(state.selectedCategory.value, undefined);
  assert.equal(state.categoryMode.value, 'view');
  assert.deepEqual(state.items.value, []);
  assert.equal(state.itemMode.value, 'view');
});

function createContext(can: (actionCode: string) => boolean = () => true): ModuleContext<DictionaryCategory> {
  const crud: ModuleContext<DictionaryCategory>['crud'] = {
    querySchema: async () => emptyQuerySchema(),
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id }),
    insert: async (record) => ({ record }),
    update: async (_id, record) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
  };
  return {
    moduleAlias: 'platform.dictionary_category',
    http: { request: async () => undefined as never },
    crud,
    runtime: fakeRuntimeState(),
    abilities: {
      crud: () => crud,
      tree: () => ({
        ...crud,
        tree: async () => ({ records: [] }),
        treeFlat: async () => ({ records: [] }),
        subtree: async () => ({ records: [] }),
        sort: async () => 1,
      }),
      enable: () => crud,
      tryCrud: () => crud,
      tryTree: () => undefined,
      tryEnable: () => crud,
      has: () => undefined,
      hasCrud: () => undefined,
      hasTree: () => undefined,
      hasEnable: () => undefined,
    },
    action: () => undefined,
    runtimeAction: () => undefined,
    can,
    recordActions: async (recordId) => ({ recordId, actions: [] }),
    recordActionsSnapshot: () => undefined,
  };
}

function createItemClient(
  overrides: Partial<StaticModuleTreeClient<DictionaryItem>> = {},
): StaticModuleTreeClient<DictionaryItem> {
  return {
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id }),
    insert: async (record) => ({ record }),
    update: async (_id, record) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
    tree: async () => ({ records: [] }),
    treeFlat: async () => ({ records: [] }),
    subtree: async () => ({ records: [] }),
    sort: async () => 1,
    ...overrides,
    querySchema: overrides.querySchema ?? (() => Promise.resolve(emptyQuerySchema())),
  };
}

function createCategoryClient(
  overrides: Partial<StaticModuleTreeClient<DictionaryCategory>> = {},
): StaticModuleTreeClient<DictionaryCategory> {
  return {
    query: async () => ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      pages: 0,
      totalKnown: true,
    }),
    view: async (id) => ({ id }),
    insert: async (record) => ({ record }),
    update: async (_id, record) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
    tree: async () => ({ records: [] }),
    treeFlat: async () => ({ records: [] }),
    subtree: async () => ({ records: [] }),
    sort: async () => 1,
    ...overrides,
    querySchema: overrides.querySchema ?? (() => Promise.resolve(emptyQuerySchema())),
  };
}

function fakeRuntimeState(): ModuleRuntimeContextState {
  return {
    ready: Promise.resolve({
      moduleAlias: 'platform.dictionary_category',
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    load: async () => ({
      moduleAlias: 'platform.dictionary_category',
      capabilities: ['CRUD', 'TREE', 'ENABLE'],
      abilities: ['crud', 'tree', 'enable'],
      actions: [],
    }),
    snapshot: () => undefined,
    error: () => undefined,
    hasAbility: () => undefined,
    action: () => undefined,
    runtimeAction: () => undefined,
    can: () => undefined,
    recordActions: async (recordId) => ({ recordId, actions: [] }),
    recordActionsSnapshot: () => undefined,
  };
}

function emptyQuerySchema() {
  return {
    scopeName: 'platform.dictionary_category',
    quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
    fields: [],
    externalCriteria: [],
    defaultSorts: [],
  };
}
