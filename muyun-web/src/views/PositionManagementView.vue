<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  ModuleActionButton,
  ManagementWorkspace,
  ManagementExplorerColumn,
  RecordActionBar,
  RecordDetailPanel,
  RecordExplorerPanel,
  RecordFormFields,
  RecordListExplorer,
  CrudRecordListExplorer,
  RecordMetaSection,
  RecordStatusSwitch,
  TreeRecordExplorer,
  createScopedTreeModuleContext,
  childResourceDefaultFormViewCode,
  presentPlatformError,
  presentPlatformMessage,
  resolveRecordFormFields,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordFormFieldFallback,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type { Option, Position, PositionCategory, Tenant } from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { confirmAction, UiEmpty, UiInput, UiSpin, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
import {
  createPositionManagementState,
  positionCategoryTitleOf,
  positionTitleOf,
} from './positionManagementState';

defineOptions({ name: 'PositionManagementView' });

type PositionFormFieldName = 'categoryId' | 'code' | 'title' | 'description';

const POSITION_RESOURCE = 'position';

const categoryContext = useModuleContext<PositionCategory>({ moduleAlias: 'iam.position_category' });
const positionContext = useModuleContext<Position>({ moduleAlias: 'iam.position' });
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const currentUser = useCurrentUserContext();
const selectedTenant = ref<Tenant>();
const tenantSearchKeyword = ref('');
const tenantReloadKey = ref(0);
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const currentUserTenant = computed<Tenant | undefined>(() => {
  const tenantId = currentUser?.value?.tenantId;
  if (currentUser?.value?.system === true || !tenantId) {
    return undefined;
  }
  return { id: tenantId, title: tenantId } as Tenant;
});
const selectedTenantId = computed(() => selectedTenant.value?.id);
const categoryScopeOptions = {
  scopeFieldName: 'tenantId',
  scopeValue: () => selectedTenantId.value,
  treePath: '/iam.position_category/tree',
};
const scopedCategoryContext = createScopedTreeModuleContext(
  categoryContext,
  categoryScopeOptions,
) as ModuleContext<PositionCategory>;
const categoryCrud = {
  ...scopedCategoryContext.crud,
  insert: (record: PositionCategory) =>
    scopedCategoryContext.crud.insert({ ...record, tenantId: selectedTenantId.value }),
  update: (id: string, record: PositionCategory) =>
    scopedCategoryContext.crud.update(id, { ...record, tenantId: selectedTenantId.value }),
};
const categoryManagementContext: ModuleContext<PositionCategory> = {
  ...scopedCategoryContext,
  crud: categoryCrud,
  abilities: {
    ...scopedCategoryContext.abilities,
    crud: () => categoryCrud,
  },
};
const categorySearchKeyword = ref('');
const positionSearchKeyword = ref('');
const positionFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const {
  categoryReloadKey,
  positionReloadKey,
  categories,
  selectedCategory,
  categoryDraft,
  categoryMode,
  categorySaving,
  selectedPosition,
  positionDraft,
  positionMode,
  positionLoading,
  positionSaving,
  selectedCategoryId,
  filteredPositions,
  canToggleCategory,
  canCreatePosition,
  canTogglePosition,
  positionReadonly,
  categoryReadonly,
  positionCardTitle,
  categoryEditorTitle,
  handleCategoriesLoaded,
  handleSelectCategory,
  startCreateRootCategory,
  startCreateChildCategory,
  startEditCategory,
  cancelCategoryEdit,
  saveCategory,
  deleteCategory,
  loadPositions,
  selectPosition,
  startCreatePosition,
  startEditPosition,
  cancelPositionEdit,
  savePosition,
  togglePosition,
  deletePosition,
} = createPositionManagementState(categoryManagementContext, positionContext.crud, confirmAction);

const categoryOptions = computed<Option[]>(() =>
  categories.value
    .filter((category) => category.id && category.enabled !== false)
    .map((category) => ({
      label: positionCategoryTitleOf(category),
      value: category.id ?? '',
    })),
);
const positionFormFieldNames: PositionFormFieldName[] = ['categoryId', 'code', 'title', 'description'];
const positionFormFieldFallback = computed<Record<PositionFormFieldName, RecordFormFieldFallback>>(() => ({
  categoryId: {
    label: '所属分类',
    required: true,
    controlType: 'select',
    options: categoryOptions.value,
    placeholder: '选择岗位分类',
  },
  code: { label: '岗位编码', required: true, placeholder: '请输入岗位编码' },
  title: { label: '岗位名称', required: true, placeholder: '请输入岗位名称' },
  description: { label: '说明' },
}));

const categoryActions = computed<RecordActionItem[]>(() => {
  return [
    { key: 'category-cancel', title: '取消', disabled: categorySaving.value },
    {
      key: 'category-save',
      actionCode: categoryMode.value.startsWith('create') ? 'create' : 'update',
      iconName: 'save',
      title: categorySaving.value ? '保存中' : '保存',
      loading: categorySaving.value,
      primary: true,
    },
  ];
});

const categoryEditorVisible = computed(() => categoryMode.value !== 'view');
const positionListEmptyDescription = computed(() =>
  positionSearchKeyword.value.trim() ? '没有匹配的岗位' : '当前分类暂无岗位',
);

const positionActions = computed<RecordActionItem[]>(() => {
  if (positionMode.value !== 'view') {
    return [
      { key: 'position-cancel', title: '取消', disabled: positionSaving.value },
      {
        key: 'position-save',
        actionCode: positionMode.value === 'create' ? 'position_create' : 'position_update',
        iconName: 'save',
        title: positionSaving.value ? '保存中' : '保存',
        loading: positionSaving.value,
        primary: true,
      },
    ];
  }
  return [
    { key: 'position-edit', actionCode: 'position_update', title: '编辑', disabled: !selectedPosition.value },
    {
      key: 'position-delete',
      actionCode: 'position_delete',
      title: '删除',
      disabled: !selectedPosition.value,
      loading: positionSaving.value,
      danger: true,
    },
  ];
});

onMounted(loadPositionFormDefinition);
onMounted(() => {
  void loadPositions();
});

watch(currentUserTenant, initializeTenantUserScope, { immediate: true });

watch(positionReloadKey, () => {
  void loadPositions();
});

watch(selectedCategoryId, () => {
  void loadPositions();
});

watch(selectedTenantId, () => {
  categoryReloadKey.value += 1;
  positionReloadKey.value += 1;
});

function handleCategoryAction(action: RecordActionItem) {
  if (action.key === 'category-cancel') {
    cancelCategoryEdit();
    return;
  }
  if (action.key === 'category-save') {
    void saveCategory();
  }
}

function selectTenant(tenant: Tenant) {
  if (tenant.id !== selectedTenant.value?.id) {
    handleCategoriesLoaded([]);
  }
  selectedTenant.value = tenant;
}

function initializeTenantUserScope(tenant = currentUserTenant.value) {
  if (!tenant || canBrowseTenants.value || selectedTenant.value) {
    return;
  }
  selectedTenant.value = tenant;
}

function startCreateRootCategoryScoped() {
  if (canBrowseTenants.value && !selectedTenantId.value) {
    presentPlatformMessage('请先选择租户', { source: 'position-management', phase: 'validation' });
    return;
  }
  startCreateRootCategory();
}

function tenantItemOf(record: Tenant): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名租户',
    secondary: record.alias ?? record.id,
  };
}

function categoryTreeActionsOf(record: PositionCategory): UiRecordInlineAction[] {
  const actions: UiRecordInlineAction[] = [];
  if (record.id && categoryContext.can('create') === true) {
    actions.push({ key: 'create-child', title: '新增下级', iconName: 'plus' });
  }
  if (record.id && categoryContext.can('update') === true) {
    actions.push({ key: 'edit', title: '编辑分类', iconName: 'edit' });
  }
  if (record.id && categoryContext.can('delete') === true) {
    actions.push({ key: 'delete', title: '删除分类', iconName: 'delete', danger: true });
  }
  return actions;
}

function categoryItemOf(record: PositionCategory): RecordExplorerItemDescriptor {
  return {
    title: positionCategoryTitleOf(record),
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
    actions: categoryTreeActionsOf(record),
  };
}

function positionItemOf(record: Position): RecordExplorerItemDescriptor {
  return {
    title: positionTitleOf(record),
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
  };
}

function handleCategoryTreeAction(action: UiRecordInlineAction, record: PositionCategory) {
  handleSelectCategory(record);
  if (action.key === 'create-child') {
    startCreateChildCategory();
    return;
  }
  if (action.key === 'edit') {
    startEditCategory();
    return;
  }
  if (action.key === 'delete') {
    void deleteCategory();
  }
}

function handlePositionAction(action: RecordActionItem) {
  if (action.key === 'position-edit') {
    startEditPosition();
    return;
  }
  if (action.key === 'position-delete') {
    void deletePosition();
    return;
  }
  if (action.key === 'position-cancel') {
    cancelPositionEdit();
    return;
  }
  if (action.key === 'position-save') {
    void savePosition();
  }
}

async function loadPositionFormDefinition() {
  try {
    const runtimeContext = await categoryContext.runtime.ready;
    positionFormFieldDefinitions.value = resolveRecordFormFields(
      runtimeContext.uiDescriptor,
      childResourceDefaultFormViewCode(POSITION_RESOURCE),
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'position-management', phase: 'load' });
  }
}

function updatePositionDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  positionDraft.value = {
    ...positionDraft.value,
    [fieldName]: value,
  };
}
</script>

<template>
  <ManagementWorkspace class="position-workspace" :explorer-count="canBrowseTenants ? 3 : 2">
    <ManagementExplorerColumn v-if="canBrowseTenants">
      <RecordExplorerPanel
        v-model:search-keyword="tenantSearchKeyword"
        title="租户"
        search-placeholder="搜索租户"
        @refresh="tenantReloadKey += 1"
      >
        <CrudRecordListExplorer
          :context="tenantContext"
          :selected-id="selectedTenant?.id"
          :reload-key="tenantReloadKey"
          :keyword="tenantSearchKeyword"
          empty-description="暂无租户"
          loading-tip="加载租户"
          fallback-title="未命名租户"
          :item-of="(record) => tenantItemOf(record as Tenant)"
          @select="selectTenant($event as Tenant)"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="categorySearchKeyword"
        class="category-column"
        title="岗位分类"
        search-placeholder="搜索分类名称、编码或 ID"
        @refresh="categoryReloadKey += 1"
      >
        <template #actions>
          <ModuleActionButton
            class="record-panel-create-button"
            :context="categoryManagementContext"
            action-code="create"
            title="新增分类"
            icon-only
            :disabled="categorySaving || (canBrowseTenants && !selectedTenantId)"
            @click="startCreateRootCategoryScoped"
          />
        </template>
        <TreeRecordExplorer
          :context="categoryManagementContext"
          :selected-id="selectedCategory?.id"
          :reload-key="categoryReloadKey"
          :keyword="categorySearchKeyword"
          search-mode="none"
          search-placeholder="搜索分类名称、编码或 ID"
          empty-description="暂无岗位分类"
          loading-tip="加载岗位分类"
          fallback-title="未命名分类"
          :item-of="categoryItemOf"
          @loaded="handleCategoriesLoaded"
          @select="handleSelectCategory"
          @action="handleCategoryTreeAction"
        />
        <template #editor>
          <Transition name="category-editor-drawer">
            <section v-if="categoryEditorVisible" class="category-editor-panel">
              <header class="category-editor-header">
                <div>
                  <h3>{{ categoryEditorTitle }}</h3>
                </div>
                <RecordActionBar
                  :context="categoryManagementContext"
                  :actions="categoryActions"
                  size="compact"
                  @action="handleCategoryAction"
                />
              </header>
              <form class="category-form" @submit.prevent="saveCategory">
                <label>
                  <span>分类编码</span>
                  <UiInput v-model:value="categoryDraft.code" :disabled="categoryReadonly" />
                </label>
                <label>
                  <span>分类名称</span>
                  <UiInput v-model:value="categoryDraft.title" :disabled="categoryReadonly" />
                </label>
                <label>
                  <span>说明</span>
                  <UiInput v-model:value="categoryDraft.description" :disabled="categoryReadonly" />
                </label>
              </form>
              <section v-if="categoryMode === 'edit' && selectedCategory?.id" class="category-status-panel">
                <RecordStatusSwitch
                  :enabled="categoryDraft.enabled"
                  :disabled="categorySaving || !canToggleCategory"
                  @change="categoryDraft.enabled = $event"
                />
              </section>
            </section>
          </Transition>
        </template>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="positionSearchKeyword"
        class="list-column"
        title="岗位"
        search-placeholder="搜索岗位名称、编码或 ID"
        @refresh="positionReloadKey += 1"
      >
        <template #actions>
          <ModuleActionButton
            class="record-panel-create-button"
            :context="categoryManagementContext"
            action-code="position_create"
            title="新增岗位"
            icon-only
            :disabled="!selectedCategory || positionSaving || !canCreatePosition"
            @click="startCreatePosition"
          />
        </template>
        <UiSpin v-if="positionLoading" tip="加载岗位列表" />
        <UiEmpty v-else-if="!selectedCategory" description="请选择岗位分类" />
        <RecordListExplorer
          v-else
          :records="filteredPositions"
          :selected-id="selectedPosition?.id"
          :keyword="positionSearchKeyword"
          :empty-description="positionListEmptyDescription"
          :item-of="(record) => positionItemOf(record as Position)"
          @select="selectPosition"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel class="position-column" :title="positionCardTitle">
      <template #status>
        <RecordStatusSwitch
          v-if="positionMode !== 'view'"
          :enabled="positionDraft.enabled"
          :show-label="false"
          @change="positionDraft.enabled = $event"
        />
        <RecordStatusSwitch
          v-else-if="selectedPosition"
          :enabled="selectedPosition.enabled"
          :disabled="positionSaving || !canTogglePosition"
          :loading="positionSaving"
          :show-label="false"
          @change="togglePosition"
        />
      </template>
      <template #actions>
        <RecordActionBar
          :context="categoryManagementContext"
          :actions="positionActions"
          @action="handlePositionAction"
        />
      </template>
      <UiEmpty v-if="!selectedPosition && positionMode === 'view'" description="请选择或新建岗位" />
      <form v-else class="position-form" @submit.prevent="savePosition">
        <RecordFormFields
          :record="positionDraft as RecordFormRecord"
          :field-names="positionFormFieldNames"
          :fields="positionFormFieldDefinitions"
          :fallback="positionFormFieldFallback"
          :disabled="positionReadonly || positionSaving"
          @update:field="updatePositionDraftField"
        />
      </form>
      <RecordMetaSection
        v-if="selectedPosition || positionMode !== 'view'"
        :record="positionDraft"
        show-sort-order
      />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>

<style scoped>
.position-workspace {
  --muyun-management-explorer-width: 280px;
  --muyun-management-detail-min-width: 560px;
}

.position-column {
  display: grid;
  align-content: start;
  min-width: 560px;
  min-height: 0;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
  container-type: inline-size;
}

.category-column,
.list-column {
  min-height: 0;
}

.record-panel-create-button {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

h2,
h3 {
  margin: 0;
}

h2 {
  color: var(--muyun-text);
  font-size: 16px;
}

h3 {
  color: var(--muyun-text);
  font-size: 14px;
}

.category-editor-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: grid;
  align-content: start;
  gap: 10px;
  max-height: min(420px, 62%);
  min-height: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-top: 1px solid var(--muyun-border-subtle);
  border-radius: 8px 8px 0 0;
  background: var(--muyun-surface);
  box-shadow:
    0 -1px 0 rgb(15 23 42 / 4%),
    0 -12px 28px rgb(15 23 42 / 12%);
  overflow: auto;
}

.category-editor-drawer-enter-active,
.category-editor-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.category-editor-drawer-enter-from,
.category-editor-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}

.category-editor-drawer-enter-to,
.category-editor-drawer-leave-from {
  opacity: 1;
  transform: translateY(0);
}

.category-editor-header h3 {
  margin: 0;
}

.category-editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.category-editor-header h3 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-editor-header :deep(.record-action-bar) {
  flex: 0 0 auto;
}

.category-form {
  display: grid;
  gap: 12px;
}

.category-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}

.position-form {
  display: grid;
  gap: 12px;
}

.category-status-panel {
  padding-top: 10px;
  border-top: 1px solid var(--muyun-border-subtle);
}

.position-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}

.position-form {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
}

.position-form :deep(.record-form-field),
.position-form :deep(.ant-input),
.position-form :deep(.ant-select) {
  min-width: 0;
  max-width: 100%;
}

@container (max-width: 520px) {
  .position-form {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 719px) {
  .position-form {
    grid-template-columns: 1fr;
  }

  .position-column {
    min-width: 0;
  }
}
</style>
