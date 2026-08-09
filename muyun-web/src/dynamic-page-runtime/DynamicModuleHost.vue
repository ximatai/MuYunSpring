<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordDetailPanel,
  RecordDetailFields,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordModeDrawer,
  RecordPanelButton,
  RecordPanelState,
  RecordQueryListPanel,
  RecordStatusSwitch,
  TreeRecordExplorer,
  confirmAction,
  parentRecordConstraints,
  presentPlatformError,
  providePageLayout,
  resolveRecordFormFields,
  type RecordFormFieldPickerConfig,
  type RecordActionItem,
  type QueryListRecord,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type {
  DynamicModulePageDescriptor,
  MenuPageMode,
  RecordInlineAction,
  ResolvedScopedListWorkspaceDescriptor,
  ResolvedViewDescriptor,
} from '@muyun/web-contracts';
import { createModuleContext, useModuleContext, type ModuleContext } from '@muyun/web-core';
import {
  canMutateDynamicModuleDetail,
  shouldCommitDynamicModuleDetailRequest,
} from './dynamicModuleDetailStateModel';

/**
 * Descriptor-driven CRUD runner shared by static and dynamic modules.
 *
 * The `DynamicModuleHost` name remains the compatibility counterpart of the
 * persisted `dynamic-module-host` page descriptor. It does not imply a
 * dynamic-only UI path.
 */
defineOptions({ name: 'DynamicModuleHost' });

const props = defineProps<{
  descriptor: DynamicModulePageDescriptor;
}>();

const context = useModuleContext<QueryListRecord>({
  moduleAlias: props.descriptor.target.moduleAlias,
});
const selectedRecord = ref<QueryListRecord>();
const editingRecord = ref<QueryListRecord>();
const editorMode = ref<'create' | 'edit' | 'view'>('view');
const detailOpen = ref(false);
const formFields = ref(resolveRecordFormFields(undefined));
const reloadKey = ref(0);
const treeReloadKey = ref(0);
const selectedTreeRecord = ref<QueryListRecord>();
const treeSearchKeyword = ref('');
const treeModule = ref(false);
const scopedListWorkspace = ref<ResolvedScopedListWorkspaceDescriptor>();
const selectedScopeRecord = ref<QueryListRecord>();
const scopeSearchKeyword = ref('');
const scopeReloadKey = ref(0);
const scopeTree = ref(false);
const scopeFormFields = ref(resolveRecordFormFields(undefined));
const scopeEditorOpen = ref(false);
const scopeEditorMode = ref<'create' | 'edit'>('create');
const scopeEditingRecord = ref<QueryListRecord>();
const scopeSaving = ref(false);
const saving = ref(false);
const fileDeletions = ref<
  Array<{
    recordPath: { nodes: Array<{ relationCode?: string; recordId: string }> };
    fieldName: string;
    fileId: string;
  }>
>([]);
const scopeFileDeletions = ref<(typeof fileDeletions.value)[number][]>([]);
const togglingEnabled = ref(false);
const detailLoading = ref(false);
const detailLoadFailed = ref(false);
let detailLoadSequence = 0;

const title = computed(
  () => props.descriptor.title ?? context.runtime.snapshot()?.title ?? context.moduleAlias,
);
const detailTitle = computed(() => {
  if (editorMode.value === 'create') return `新建${title.value}`;
  return recordTitle(editingRecord.value ?? selectedRecord.value) ?? '记录详情';
});
const scopeEditorTitle = computed(
  () =>
    `${scopeEditorMode.value === 'create' ? '新建' : '编辑'}${scopedListWorkspace.value?.scopeTitle ?? '目录'}`,
);
const pageMode = computed<MenuPageMode>(() => props.descriptor.target.pageMode ?? 'LIST');
const isListPage = computed(() => pageMode.value === 'LIST');
const listUiConfigId = computed(() =>
  isListPage.value ? props.descriptor.target.defaultUiConfigId : undefined,
);
const unsupportedPageModeText = computed(() => `动态${pageMode.value}入口暂未接入运行器`);
// Tree modules are discovered from runtime metadata. Once discovered, their
// explorer/detail panes own the constrained work area instead of extending the
// workbench tab's document flow.
providePageLayout(
  computed(() => (treeModule.value || scopedListWorkspace.value ? 'workspace' : props.descriptor.layout)),
);
const scopeContext = computed<ModuleContext<QueryListRecord> | undefined>(() => {
  const workspace = scopedListWorkspace.value;
  return workspace
    ? createModuleContext({ http: context.http, moduleAlias: workspace.scopeModuleAlias })
    : undefined;
});
const scopeSelectionRequired = computed(() => scopedListWorkspace.value?.createPolicy === 'REQUIRE_SCOPE');
const canCreateRecord = computed(
  () => !scopeSelectionRequired.value || selectedScopeRecord.value?.id != null,
);
const scopedExternalQueryValues = computed<Record<string, unknown> | undefined>(() => {
  const workspace = scopedListWorkspace.value;
  const id = selectedScopeRecord.value?.id;
  return workspace && id != null ? { [workspace.queryCriteriaKey]: id } : undefined;
});
const scopedListActions = computed<RecordActionItem[]>(() => [
  {
    key: 'create',
    actionCode: 'create',
    title: '新建',
    primary: true,
    disabled: !canCreateRecord.value,
  },
]);
const canToggleEnabled = computed(() => {
  const record = selectedRecord.value;
  if (
    !record?.id ||
    editorMode.value !== 'view' ||
    detailLoading.value ||
    detailLoadFailed.value ||
    togglingEnabled.value
  ) {
    return false;
  }
  return context.can(record.enabled === false ? 'enable' : 'disable') === true;
});
const treeParentPickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  if (!treeModule.value || !formFields.value.has('parentId')) {
    return {} as Record<string, RecordFormFieldPickerConfig>;
  }
  return {
    parentId: {
      context,
      mode: 'tree',
      placeholder: '根标签留空',
      allowClear: true,
      constraints: parentRecordConstraints(
        editingRecord.value?.id == null ? undefined : String(editingRecord.value.id),
      ),
    },
  };
});
const referencePickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const configs: Record<string, RecordFormFieldPickerConfig> = { ...treeParentPickerConfigs.value };
  for (const field of formFields.value.values()) {
    const reference = field.reference;
    if (!reference) {
      continue;
    }
    configs[field.fieldRef.fieldName] = {
      context: createModuleContext({ http: context.http, moduleAlias: reference.targetModuleAlias }),
      mode: 'tree',
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});

onMounted(loadRuntimeForm);

async function loadRuntimeForm() {
  if (!isListPage.value) {
    return;
  }
  const runtimeContext = await context.runtime.ready;
  treeModule.value = context.abilities.hasTree() === true;
  scopedListWorkspace.value = scopedListWorkspaceFor(runtimeContext.uiDescriptor?.views ?? []);
  scopeTree.value = false;
  if (scopeContext.value) {
    const scopeRuntime = await scopeContext.value.runtime.ready;
    scopeTree.value = scopeContext.value.abilities.hasTree() === true;
    scopeFormFields.value = resolveRecordFormFields(scopeRuntime.uiDescriptor);
  }
  const view = defaultFormView(runtimeContext.uiDescriptor?.views ?? []);
  formFields.value = resolveRecordFormFields(runtimeContext.uiDescriptor, view?.viewCode);
}

const scopeParentPickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  if (!scopeContext.value || !scopeTree.value || !scopeFormFields.value.has('parentId')) {
    return {} as Record<string, RecordFormFieldPickerConfig>;
  }
  return {
    parentId: {
      context: scopeContext.value,
      mode: 'tree',
      placeholder: `根${scopedListWorkspace.value?.scopeTitle ?? '目录'}留空`,
      allowClear: true,
      constraints: parentRecordConstraints(
        scopeEditingRecord.value?.id == null ? undefined : String(scopeEditingRecord.value.id),
      ),
    },
  };
});

/** Scope editors may contain ordinary references as well as the tree's parent field. */
const scopeReferencePickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const configs: Record<string, RecordFormFieldPickerConfig> = { ...scopeParentPickerConfigs.value };
  if (!scopeContext.value) {
    return configs;
  }
  for (const field of scopeFormFields.value.values()) {
    const fieldName = field.fieldRef.fieldName;
    const reference = field.reference;
    if (!reference || fieldName === 'parentId') {
      continue;
    }
    configs[fieldName] = {
      context: createModuleContext({ http: context.http, moduleAlias: reference.targetModuleAlias }),
      mode: 'tree',
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});

function defaultFormView(views: ResolvedViewDescriptor[]) {
  return views.find((view) => view.viewKind === 'FORM');
}

function scopedListWorkspaceFor(
  views: ResolvedViewDescriptor[],
): ResolvedScopedListWorkspaceDescriptor | undefined {
  const listViews = views.filter((view) => view.viewKind === 'LIST');
  const configuredList =
    listUiConfigId.value == null
      ? undefined
      : listViews.find((view) => view.sourceUiConfigId === listUiConfigId.value);
  if (configuredList) {
    return configuredList.scopedListWorkspace;
  }
  return listUiConfigId.value == null && listViews.length === 1
    ? listViews[0].scopedListWorkspace
    : undefined;
}

function handleLoaded(records: QueryListRecord[]) {
  if (selectedRecord.value) {
    selectedRecord.value =
      records.find((record) => record.id === selectedRecord.value?.id) ?? selectedRecord.value;
    // The list is intentionally a compact projection. Do not overwrite an open
    // detail snapshot with it after a refresh (for example, enable/disable), or
    // form-only/read-side fields disappear from the drawer. `openRecord` and
    // `toggleEnabled` refresh the authoritative detail through CRUD view.
  }
}

function selectRecord(record: QueryListRecord) {
  selectedRecord.value = record;
}

function selectScopeRecord(record: { id?: string }) {
  if (selectedScopeRecord.value?.id === record.id) {
    selectedScopeRecord.value = undefined;
    return;
  }
  selectedScopeRecord.value = record as QueryListRecord;
}

function scopeTreeActions(): RecordInlineAction[] {
  if (!scopedListWorkspace.value?.manageScopeTree || !scopeTree.value || !scopeContext.value) return [];
  const actions: RecordInlineAction[] = [];
  if (scopeContext.value.can('create') === true)
    actions.push({ key: 'create-child', title: '新增下级', iconName: 'plus' });
  if (scopeContext.value.can('update') === true)
    actions.push({ key: 'edit', title: '编辑', iconName: 'edit' });
  if (scopeContext.value.can('delete') === true)
    actions.push({ key: 'delete', title: '删除', iconName: 'delete', danger: true });
  return actions;
}

function openScopeEditor(mode: 'create' | 'edit', record?: QueryListRecord) {
  scopeFileDeletions.value = [];
  scopeEditorMode.value = mode;
  scopeEditingRecord.value =
    mode === 'create'
      ? record?.id == null
        ? { enabled: true }
        : { parentId: record.id, enabled: true }
      : record;
  scopeEditorOpen.value = true;
}

function closeScopeEditor() {
  if (scopeSaving.value) return;
  scopeEditorOpen.value = false;
  scopeFileDeletions.value = [];
}

async function editScopeRecord(record: QueryListRecord) {
  if (!scopeContext.value?.crud || record.id == null) return;
  try {
    const detail = await scopeContext.value.crud.view(String(record.id));
    openScopeEditor('edit', detail as QueryListRecord);
  } catch (cause) {
    presentPlatformError(cause, { source: 'scoped-tree-editor', phase: 'load' });
  }
}

async function saveScopeRecord() {
  const scope = scopeContext.value;
  const record = scopeEditingRecord.value;
  if (!scope || !record || scopeSaving.value) return;
  scopeSaving.value = true;
  try {
    const result =
      scopeEditorMode.value === 'edit' && record.id != null
        ? await scope.crud.update(String(record.id), record, { fileDeletions: scopeFileDeletions.value })
        : await scope.crud.insert(record, { fileDeletions: scopeFileDeletions.value });
    selectedScopeRecord.value = result.record as QueryListRecord;
    scopeEditorOpen.value = false;
    scopeFileDeletions.value = [];
    scopeReloadKey.value += 1;
  } catch (cause) {
    presentPlatformError(cause, { source: 'scoped-tree-editor', phase: 'action' });
  } finally {
    scopeSaving.value = false;
  }
}

async function deleteScopeRecord(record: QueryListRecord) {
  const scope = scopeContext.value;
  const id = record.id == null ? undefined : String(record.id);
  const version = typeof record.version === 'number' ? record.version : undefined;
  if (!scope || !id || version === undefined) return;
  try {
    if (
      !(await confirmAction({
        title: `删除${scopedListWorkspace.value?.scopeTitle ?? '目录'}`,
        content: `确认删除「${recordTitle(record) ?? id}」？`,
        okText: '删除',
        danger: true,
      }))
    )
      return;
    await scope.crud.delete(id, { version });
    if (selectedScopeRecord.value?.id === id) selectedScopeRecord.value = undefined;
    scopeReloadKey.value += 1;
  } catch (cause) {
    presentPlatformError(cause, { source: 'scoped-tree-editor', phase: 'action' });
  }
}

function handleScopeTreeAction(action: RecordInlineAction, record: unknown) {
  const scopeRecord = record as QueryListRecord;
  selectScopeRecord(scopeRecord);
  if (action.key === 'create-child') openScopeEditor('create', scopeRecord);
  if (action.key === 'edit') void editScopeRecord(scopeRecord);
  if (action.key === 'delete') void deleteScopeRecord(scopeRecord);
}

function updateScopeDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  if (!scopeEditingRecord.value) return;
  scopeEditingRecord.value = { ...scopeEditingRecord.value, [fieldName]: value };
}

function addScopeFileDeletion(intent: (typeof scopeFileDeletions.value)[number]) {
  if (
    !scopeFileDeletions.value.some(
      (candidate) => candidate.fieldName === intent.fieldName && candidate.fileId === intent.fileId,
    )
  ) {
    scopeFileDeletions.value = [...scopeFileDeletions.value, intent];
  }
}

function selectTreeRecord(record: unknown) {
  selectedTreeRecord.value = record as QueryListRecord;
  void openRecord(selectedTreeRecord.value, 'view');
}

function handleTreeLoaded(records: unknown[]) {
  if (selectedTreeRecord.value || editorMode.value !== 'view') return;
  const firstRecord = records.at(0);
  if (firstRecord) selectTreeRecord(firstRecord);
}

async function openRecord(record: QueryListRecord, mode: 'edit' | 'view') {
  const id = record.id == null ? undefined : String(record.id);
  if (!id) return;
  const requestSequence = ++detailLoadSequence;
  fileDeletions.value = [];
  selectedRecord.value = record;
  editingRecord.value = undefined;
  editorMode.value = mode;
  detailOpen.value = true;
  detailLoading.value = true;
  detailLoadFailed.value = false;
  try {
    const detail = await context.crud.view(id);
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    )
      return;
    editingRecord.value = detail;
    selectedRecord.value = detail;
  } catch {
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    )
      return;
    editingRecord.value = undefined;
    detailLoadFailed.value = true;
  } finally {
    if (
      shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    ) {
      detailLoading.value = false;
    }
  }
}

function updateDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  if (!editingRecord.value) {
    return;
  }
  editingRecord.value = {
    ...editingRecord.value,
    [fieldName]: value,
  };
}

function addFileDeletion(intent: (typeof fileDeletions.value)[number]) {
  if (
    !fileDeletions.value.some(
      (candidate) => candidate.fieldName === intent.fieldName && candidate.fileId === intent.fileId,
    )
  ) {
    fileDeletions.value = [...fileDeletions.value, intent];
  }
}

function createRecord(parentId?: string) {
  if (scopeSelectionRequired.value && selectedScopeRecord.value?.id == null) return;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  fileDeletions.value = [];
  const workspace = scopedListWorkspace.value;
  editingRecord.value = parentId
    ? { parentId }
    : workspace && selectedScopeRecord.value?.id != null
      ? { [workspace.scopeField]: selectedScopeRecord.value.id }
      : {};
  editorMode.value = 'create';
  detailOpen.value = true;
}

function createRootRecord() {
  createRecord();
}

function createChildRecord() {
  const parentId = selectedRecord.value?.id == null ? undefined : String(selectedRecord.value.id);
  if (parentId) createRecord(parentId);
}

async function editRecord(record: QueryListRecord) {
  await openRecord(record, 'edit');
}

async function saveRecord() {
  const record = editingRecord.value;
  if (!record) return;
  if (
    !canMutateDynamicModuleDetail({
      hasRecord: true,
      saving: saving.value,
      loading: detailLoading.value,
      loadFailed: detailLoadFailed.value,
    })
  ) {
    return;
  }
  saving.value = true;
  try {
    const id = record.id == null ? undefined : String(record.id);
    const result =
      editorMode.value === 'edit' && id
        ? await context.crud.update(id, record, { fileDeletions: fileDeletions.value })
        : await context.crud.insert(record, { fileDeletions: fileDeletions.value });
    selectedRecord.value = result.record;
    if (treeModule.value) {
      selectedTreeRecord.value = result.record;
    }
    editingRecord.value = result.record;
    editorMode.value = 'view';
    reloadKey.value += 1;
    treeReloadKey.value += 1;
    fileDeletions.value = [];
  } finally {
    saving.value = false;
  }
}

async function deleteRecord(record: QueryListRecord) {
  const id = record.id == null ? undefined : String(record.id);
  const version = typeof record.version === 'number' ? record.version : undefined;
  if (!id || version === undefined) return;
  if (
    !(await confirmAction({
      title: '删除记录',
      content: `确认删除「${recordTitle(record) ?? id}」？`,
      okText: '删除',
      danger: true,
    }))
  ) {
    return;
  }
  await context.crud.delete(id, { version });
  if (selectedRecord.value?.id === id) {
    selectedRecord.value = undefined;
    editingRecord.value = undefined;
    selectedTreeRecord.value = undefined;
  }
  reloadKey.value += 1;
  treeReloadKey.value += 1;
}

async function toggleEnabled() {
  const record = selectedRecord.value;
  const id = record?.id == null ? undefined : String(record.id);
  const version = typeof record?.version === 'number' ? record.version : undefined;
  if (!record || !id || version === undefined || !canToggleEnabled.value) return;

  togglingEnabled.value = true;
  try {
    if (record.enabled === false) {
      await context.crud.enable(id, { version });
    } else {
      await context.crud.disable(id, { version });
    }
    const refreshed = await context.crud.view(id);
    selectedRecord.value = refreshed;
    editingRecord.value = refreshed;
    reloadKey.value += 1;
    treeReloadKey.value += 1;
  } finally {
    togglingEnabled.value = false;
  }
}

function handleListAction(action: { key?: string }) {
  if (action.key === 'create') createRecord();
}

function handleRowAction(action: { key?: string }, record: QueryListRecord) {
  if (action.key === 'view') void openRecord(record, 'view');
  if (action.key === 'edit') void editRecord(record);
  if (action.key === 'delete') void deleteRecord(record);
}

function closeDetail() {
  if (saving.value) return;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  fileDeletions.value = [];
  detailOpen.value = false;
  editorMode.value = 'view';
  editingRecord.value = selectedRecord.value;
}

function closeTreeCardEditor() {
  if (saving.value) return;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  fileDeletions.value = [];
  detailOpen.value = false;
  editorMode.value = 'view';
  editingRecord.value = selectedRecord.value;
}

function retryLoadDetail() {
  const record = selectedRecord.value;
  if (!record || editorMode.value === 'create') return;
  void openRecord(record, editorMode.value);
}

function recordTitle(record: QueryListRecord | undefined) {
  const titleValue = record?.title ?? record?.name ?? record?.code ?? record?.id;
  return titleValue == null ? undefined : String(titleValue);
}
</script>

<template>
  <section
    v-if="isListPage"
    class="dynamic-module-workspace"
    :class="{ 'dynamic-module-workspace--tree': treeModule }"
  >
    <ManagementWorkspace v-if="scopedListWorkspace && scopeContext" class="dynamic-tree-workspace">
      <ManagementExplorerColumn>
        <RecordExplorerPanel
          :title="scopedListWorkspace.scopeTitle"
          :refresh-title="`刷新${scopedListWorkspace.scopeTitle}${scopeTree ? '树' : '列表'}`"
          :search-keyword="scopeSearchKeyword"
          :search-placeholder="scopedListWorkspace.scopeSearchPlaceholder"
          @update:search-keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
        >
          <template v-if="scopedListWorkspace.manageScopeTree && scopeTree" #actions>
            <ModuleActionButton
              class="record-panel-create-button"
              :context="scopeContext"
              action-code="create"
              icon-only
              :title="`新增${scopedListWorkspace.scopeTitle}`"
              @click="openScopeEditor('create')"
            />
          </template>
          <TreeRecordExplorer
            v-if="scopeTree"
            :context="scopeContext"
            :selected-id="selectedScopeRecord?.id == null ? undefined : String(selectedScopeRecord.id)"
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            search-mode="none"
            :empty-description="`暂无${scopedListWorkspace.scopeTitle}`"
            :secondary-of="scopedListWorkspace.showScopeItemSubtitle ? undefined : () => undefined"
            :actions-of="scopeTreeActions"
            @select="selectScopeRecord"
            @action="handleScopeTreeAction"
          />
          <CrudRecordListExplorer
            v-else
            :context="scopeContext"
            :selected-id="selectedScopeRecord?.id == null ? undefined : String(selectedScopeRecord.id)"
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            :empty-description="`暂无${scopedListWorkspace.scopeTitle}`"
            :subtitle-of="scopedListWorkspace.showScopeItemSubtitle ? undefined : () => undefined"
            @select="selectScopeRecord"
          />
          <template #editor>
            <Transition name="dynamic-scope-editor-drawer">
              <section
                v-if="
                  scopedListWorkspace.manageScopeTree && scopeTree && scopeEditingRecord && scopeEditorOpen
                "
                class="dynamic-scope-editor-panel"
              >
                <header class="dynamic-scope-editor-header">
                  <h3>{{ scopeEditorTitle }}</h3>
                  <div class="dynamic-scope-editor-actions">
                    <RecordStatusSwitch
                      :enabled="scopeEditingRecord.enabled !== false"
                      :disabled="scopeSaving"
                      :show-label="false"
                      @change="updateScopeDraftField('enabled', $event)"
                    />
                    <RecordPanelButton :disabled="scopeSaving" @click="closeScopeEditor">
                      取消
                    </RecordPanelButton>
                    <RecordPanelButton type="primary" :loading="scopeSaving" @click="saveScopeRecord">
                      {{ scopeSaving ? '保存中' : '保存' }}
                    </RecordPanelButton>
                  </div>
                </header>
                <RecordFormFields
                  class="dynamic-scope-editor-form"
                  :record="scopeEditingRecord"
                  :fields="scopeFormFields"
                  :file-transfer-context="scopeContext"
                  :picker-configs="scopeReferencePickerConfigs"
                  :disabled="scopeSaving"
                  :exclude-field-names="['enabled']"
                  @update:field="updateScopeDraftField"
                  @file-deletion="addScopeFileDeletion"
                />
              </section>
            </Transition>
          </template>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>
      <RecordQueryListPanel
        class="dynamic-list"
        :context="context"
        :title="title"
        :selected-key="selectedRecord?.id"
        :reload-key="reloadKey"
        :actions="scopedListActions"
        :standard-crud-row-actions="true"
        :ui-config-id="listUiConfigId"
        :query-template-id="descriptor.target.defaultQueryTemplateId"
        :external-query-values="scopedExternalQueryValues"
        :required-external-criteria-keys="[scopedListWorkspace.queryCriteriaKey]"
        quick-search-placeholder="搜索动态记录"
        empty-description="暂无动态记录"
        @loaded="handleLoaded"
        @select="selectRecord"
        @row-dblclick="(record) => openRecord(record, 'view')"
        @action="handleListAction"
        @row-action="handleRowAction"
      />
    </ManagementWorkspace>

    <ManagementWorkspace v-else-if="treeModule" class="dynamic-tree-workspace">
      <ManagementExplorerColumn>
        <RecordExplorerPanel
          :title="`${title}树`"
          :refresh-title="`刷新${title}树`"
          :search-keyword="treeSearchKeyword"
          search-placeholder="搜索树节点"
          @update:search-keyword="treeSearchKeyword = $event"
          @refresh="treeReloadKey += 1"
        >
          <template #actions>
            <ModuleActionButton
              class="record-panel-create-button"
              :context="context"
              action-code="create"
              icon-only
              title="新建根节点"
              @click="createRootRecord"
            />
          </template>
          <TreeRecordExplorer
            :context="context"
            :selected-id="selectedTreeRecord?.id == null ? undefined : String(selectedTreeRecord.id)"
            :reload-key="treeReloadKey"
            :keyword="treeSearchKeyword"
            search-mode="none"
            search-trigger="external"
            empty-description="暂无记录"
            @select="selectTreeRecord"
            @loaded="handleTreeLoaded"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel class="dynamic-tree-card" :title="detailTitle">
        <template #actions>
          <template v-if="editorMode !== 'view'">
            <RecordPanelButton :disabled="saving" @click="closeTreeCardEditor">取消</RecordPanelButton>
            <RecordPanelButton
              type="primary"
              :loading="saving"
              :disabled="
                detailLoading ||
                detailLoadFailed ||
                context.can(editorMode === 'create' ? 'create' : 'update') !== true
              "
              @click="saveRecord"
            >
              {{ saving ? '保存中' : '保存' }}
            </RecordPanelButton>
          </template>
          <template v-else>
            <ModuleActionButton
              :context="context"
              action-code="create"
              :disabled="!selectedRecord"
              @click="createChildRecord"
            >
              新建子项
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="update"
              :disabled="!selectedRecord"
              @click="selectedRecord && editRecord(selectedRecord)"
            >
              编辑
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="delete"
              :loading="saving"
              danger
              :disabled="!selectedRecord"
              @click="selectedRecord && deleteRecord(selectedRecord)"
            >
              删除
            </ModuleActionButton>
          </template>
        </template>
        <template #status>
          <RecordStatusSwitch
            v-if="editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          description="请选择标签，或新建根标签"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择标签" />
        <template v-else-if="editingRecord">
          <RecordDetailFields
            v-if="editorMode === 'view'"
            :record="editingRecord as RecordFormRecord"
            :fields="formFields"
            :exclude-field-names="['enabled']"
          />
          <RecordFormFields
            v-else
            class="dynamic-form"
            :record="editingRecord as RecordFormRecord"
            :fields="formFields"
            :option-context="context"
            :picker-configs="referencePickerConfigs"
            :exclude-field-names="['enabled']"
            @update:field="updateDraftField"
            @file-deletion="addFileDeletion"
          />
          <RecordMetaSection v-if="editorMode !== 'create'" :record="editingRecord" show-sort-order />
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <RecordQueryListPanel
      v-else
      class="dynamic-list"
      :context="context"
      :title="title"
      :selected-key="selectedRecord?.id"
      :reload-key="reloadKey"
      :standard-crud-actions="true"
      :standard-crud-row-actions="true"
      :ui-config-id="listUiConfigId"
      :query-template-id="descriptor.target.defaultQueryTemplateId"
      quick-search-placeholder="搜索动态记录"
      empty-description="暂无动态记录"
      @loaded="handleLoaded"
      @select="selectRecord"
      @row-dblclick="(record) => openRecord(record, 'view')"
      @action="handleListAction"
      @row-action="handleRowAction"
    />

    <RecordModeDrawer
      v-if="!treeModule"
      :open="detailOpen"
      :title="detailTitle"
      :mode="editorMode"
      :loading="detailLoading"
      :load-failed="detailLoadFailed"
      :edit-available="
        Boolean(selectedRecord) && !detailLoading && !detailLoadFailed && editorMode === 'view'
      "
      :save-available="!detailLoading && !detailLoadFailed && editorMode !== 'view'"
      :saving="saving"
      @close="closeDetail"
      @retry="retryLoadDetail"
      @edit="selectedRecord && editRecord(selectedRecord)"
      @save="saveRecord"
    >
      <template #status>
        <RecordStatusSwitch
          v-if="editorMode === 'view' && selectedRecord"
          :enabled="selectedRecord.enabled !== false"
          :disabled="!canToggleEnabled"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template #view>
        <RecordDetailFields
          v-if="editingRecord"
          :record="editingRecord as RecordFormRecord"
          :fields="formFields"
          :exclude-field-names="['enabled']"
        />
      </template>
      <template #form>
        <RecordFormFields
          v-if="editingRecord"
          class="dynamic-form"
          :record="editingRecord as RecordFormRecord"
          :fields="formFields"
          :option-context="context"
          :picker-configs="referencePickerConfigs"
          :exclude-field-names="['enabled']"
          @update:field="updateDraftField"
          @file-deletion="addFileDeletion"
        />
      </template>
    </RecordModeDrawer>
  </section>
  <section v-else class="dynamic-module-unsupported">
    <h2>{{ title }}</h2>
    <p>{{ unsupportedPageModeText }}</p>
  </section>
</template>

<style scoped>
.dynamic-module-workspace {
  min-width: 0;
  min-height: calc(100vh - 116px);
}

/*
 * Tree metadata is loaded at runtime, so this boundary cannot be declared by
 * the menu descriptor. Keep the workbench tab fixed and let the explorer and
 * detail panels manage their own vertical scroll areas.
 */
.dynamic-module-workspace--tree {
  height: 100%;
  min-height: 0;
}

.dynamic-list {
  min-width: 0;
}

.dynamic-tree-workspace {
  height: 100%;
  min-height: 0;
}

.dynamic-tree-card {
  min-width: 0;
}

.dynamic-tree-workspace :deep(.record-panel-create-button) {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.dynamic-scope-editor-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: grid;
  align-content: start;
  gap: 12px;
  max-height: min(420px, 68%);
  min-height: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-top-color: var(--muyun-border-subtle);
  border-radius: 8px 8px 0 0;
  background: var(--muyun-surface);
  box-shadow:
    0 -1px 0 rgb(15 23 42 / 4%),
    0 -12px 28px rgb(15 23 42 / 12%);
  overflow: auto;
}

.dynamic-scope-editor-drawer-enter-active,
.dynamic-scope-editor-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.dynamic-scope-editor-drawer-enter-from,
.dynamic-scope-editor-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}

.dynamic-scope-editor-header,
.dynamic-scope-editor-actions {
  display: flex;
  align-items: center;
}

.dynamic-scope-editor-header {
  justify-content: space-between;
  gap: 10px;
}

.dynamic-scope-editor-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dynamic-scope-editor-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.dynamic-scope-editor-form {
  grid-template-columns: minmax(0, 1fr);
}

.dynamic-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dynamic-module-unsupported {
  display: grid;
  align-content: center;
  justify-items: center;
  min-height: calc(100vh - 116px);
  color: #64748b;
  text-align: center;
}

.dynamic-module-unsupported h2 {
  margin: 0 0 8px;
  color: #111827;
  font-size: 18px;
  font-weight: 600;
}

.dynamic-module-unsupported p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 720px) {
  .dynamic-module-workspace--tree {
    height: auto;
    min-height: calc(100vh - 116px);
  }

  .dynamic-tree-workspace {
    height: auto;
    min-height: 0;
  }

  .dynamic-form {
    grid-template-columns: 1fr;
  }
}
</style>
