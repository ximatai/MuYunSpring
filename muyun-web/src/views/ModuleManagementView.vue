<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  type CrudRecordListBase,
  ModuleActionButton,
  ManagementWorkspace,
  RecordActionBar,
  RecordDetailPanel,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordStatusSwitch,
  TreeRecordExplorer,
  createStaticTreeResourceModuleContext,
  parentRecordConstraints,
  presentPlatformError,
  resolveRecordFormFields,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormRecord,
  type RecordPickerRecord,
  type TreeRecordBase,
} from '@muyun/platform-components';
import type { Application, PlatformModule, WebListResponse, WebTreeNode } from '@muyun/web-contracts';
import {
  createStaticResourceCrudClient,
  useModuleContext,
  type ModuleContext,
  type StaticModuleTreeClient,
} from '@muyun/web-core';
import { confirmAction, UiEmpty, UiInput, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
import { createModuleManagementState, moduleTitleOf } from './moduleManagementState';
import {
  createModuleOpenApiPageDescriptor,
  loadOpenApiCatalog,
} from '../platform-admin-runtime/moduleOpenApi';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import { createWorkspaceViewDescriptor } from '../platform-admin-runtime/workspaceViews';
import { moduleActionManagementWorkspaceView } from './moduleActionManagementWorkspaceView';
import { metadataOrchestrationWorkspaceView } from './metadataOrchestrationWorkspaceView';

defineOptions({ name: 'ModuleManagementView' });

type ModuleFormFieldName =
  'alias' | 'title' | 'parentId' | 'moduleKind' | 'entryType' | 'entryRoute' | 'entryExternalUrl' | 'enabled';

const applicationContext = useModuleContext<Application>({ moduleAlias: 'platform.application' });
const moduleContext = useModuleContext<PlatformModule>({ moduleAlias: 'platform.module' });
const applications = ref<Application[]>([]);
const selectedApplicationAlias = ref<string>();
const applicationSearchKeyword = ref('');
const applicationReloadKey = ref(0);
const moduleSearchKeyword = ref('');
const moduleFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const treeClients = new Map<string, StaticModuleTreeClient<PlatformModule>>();
const openApiModuleAliases = ref(new Set<string>());
const workbenchNavigation = useWorkbenchNavigation();

const {
  moduleReloadKey,
  selectedModule,
  draft,
  mode,
  saving,
  readonly,
  aliasReadonly,
  canCreate,
  canToggle,
  cardTitle,
  handleModulesLoaded,
  selectModule,
  startCreateRoot,
  startCreateChild,
  startEdit,
  cancelEdit,
  save,
  toggleEnabled,
  removeSelected,
  resetForApplication,
} = createModuleManagementState(moduleContext, () => selectedApplicationAlias.value, confirmAction);

const moduleTreeContext = computed(() =>
  createStaticTreeResourceModuleContext(moduleContext, {
    client: selectedApplicationAlias.value ? moduleTreeClientOf(selectedApplicationAlias.value) : undefined,
    emptyQueryScopeName: 'platform.module',
  }),
);
const modulePickerConfigs = computed<Record<'parentId', RecordFormFieldPickerConfig>>(() => ({
  parentId: {
    context: moduleTreeContext.value as unknown as ModuleContext<RecordPickerRecord>,
    reloadKey: moduleReloadKey.value,
    placeholder: '根模块留空',
    constraints: parentRecordConstraints(draft.value.id),
    titleOf: (record) => moduleTitleOf(record as PlatformModule),
  },
}));
const formDisabled = computed(() => readonly.value || saving.value);
const moduleFormFieldNames = computed(() => [
  'alias',
  'title',
  'parentId',
  'moduleKind',
  'entryType',
  ...(draft.value.entryType === 'route'
    ? ['entryRoute']
    : draft.value.entryType === 'link'
      ? ['entryExternalUrl']
      : []),
]);

const moduleActions = computed<RecordActionItem[]>(() => {
  if (mode.value !== 'view') {
    return [
      { key: 'cancel', title: '取消', disabled: saving.value },
      {
        key: 'save',
        actionCode: mode.value.startsWith('create') ? 'create' : 'update',
        title: saving.value ? '保存中' : '保存',
        primary: true,
        loading: saving.value,
      },
    ];
  }
  const actions: RecordActionItem[] = [
    {
      key: 'edit',
      actionCode: 'update',
      title: '编辑',
      disabled: !selectedModule.value || selectedModule.value.systemManaged === true,
    },
    { key: 'create-child', actionCode: 'create', title: '新建下级', disabled: !selectedModule.value },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      danger: true,
      disabled: !selectedModule.value || selectedModule.value.systemManaged === true,
      loading: saving.value,
    },
  ];
  const moduleAlias = selectedModule.value?.alias ?? selectedModule.value?.id;
  if (moduleAlias) {
    actions.unshift({ key: 'actions', title: '动作' });
  }
  if (moduleAlias && selectedModule.value?.moduleKind === 'dynamic') {
    actions.unshift({ key: 'metadata-orchestration', title: '元数据编排' });
  }
  if (moduleAlias && openApiModuleAliases.value.has(moduleAlias)) {
    actions.unshift({ key: 'openapi', title: '查看 OpenAPI' });
  }
  return actions;
});

watch(selectedApplicationAlias, () => {
  moduleSearchKeyword.value = '';
  resetForApplication();
});

onMounted(() => {
  void loadModuleFormDefinition();
  void loadOpenApiModules();
});

function loadApplications(records: CrudRecordListBase[]) {
  applications.value = records as Application[];
  const current = applications.value.find(
    (application) => (application.alias ?? application.id) === selectedApplicationAlias.value,
  );
  const first =
    applications.value.find((application) => application.enabled !== false) ?? applications.value[0];
  selectedApplicationAlias.value = current?.alias ?? current?.id ?? first?.alias ?? first?.id;
}

function applicationItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名应用',
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}

function selectApplication(record: CrudRecordListBase) {
  selectedApplicationAlias.value = record.alias ?? record.id;
}

async function loadModuleFormDefinition() {
  try {
    const runtimeContext = await moduleContext.runtime.ready;
    moduleFormFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-management', phase: 'load' });
  }
}

async function loadOpenApiModules() {
  try {
    openApiModuleAliases.value = new Set(
      (await loadOpenApiCatalog(moduleContext.http)).map((item) => item.moduleAlias),
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-management', phase: 'load' });
  }
}

function moduleTreeClientOf(applicationAlias: string): StaticModuleTreeClient<PlatformModule> {
  const existing = treeClients.get(applicationAlias);
  if (existing) return existing;
  const crud = createStaticResourceCrudClient<PlatformModule>(moduleContext.http, '/platform.module');
  const treePath = `/platform.module/tree/${encodeURIComponent(applicationAlias)}`;
  const client: StaticModuleTreeClient<PlatformModule> = {
    ...crud,
    tree: () => moduleContext.http.request<WebListResponse<WebTreeNode<PlatformModule>>>({ path: treePath }),
    treeFlat: (options) =>
      moduleContext.http.request<WebListResponse<PlatformModule>>({
        path: options?.rootId ? `${treePath}/${encodeURIComponent(options.rootId)}` : treePath,
        query: { flat: true, includeSelf: options?.includeSelf },
      }),
    subtree: (id, options) =>
      moduleContext.http.request<WebListResponse<WebTreeNode<PlatformModule>>>({
        path: `${treePath}/${encodeURIComponent(id)}`,
        query: options,
      }),
    sort: (id, request) =>
      moduleContext.http.request<number>({
        method: 'POST',
        path: `/platform.module/sort/${encodeURIComponent(id)}`,
        body: request,
      }),
  };
  treeClients.set(applicationAlias, client);
  return client;
}

function moduleItemOf(record: TreeRecordBase): RecordExplorerItemDescriptor {
  const module = record as PlatformModule;
  return {
    title: moduleTitleOf(module),
    secondary: module.alias ?? module.id,
    tag: module.moduleKind === 'dynamic' ? '动态' : '静态',
    muted: module.enabled === false,
    actions: moduleTreeActionsOf(module),
  };
}

function moduleTreeActionsOf(record: PlatformModule): UiRecordInlineAction[] {
  if (!record.id) return [];
  const actions: UiRecordInlineAction[] = [];
  if (moduleContext.can('create') === true)
    actions.push({ key: 'create-child', title: '新增下级', iconName: 'plus' });
  if (moduleContext.can('update') === true && record.systemManaged !== true) {
    actions.push({ key: 'edit', title: '编辑模块', iconName: 'edit' });
  }
  if (moduleContext.can('delete') === true && record.systemManaged !== true) {
    actions.push({ key: 'delete', title: '删除模块', iconName: 'delete', danger: true });
  }
  return actions;
}

function handleModuleTreeAction(action: UiRecordInlineAction, record: PlatformModule) {
  selectModule(record);
  if (action.key === 'create-child') startCreateChild(record);
  if (action.key === 'edit') startEdit();
  if (action.key === 'delete') void removeSelected();
}

function handleModuleAction(action: RecordActionItem) {
  if (action.key === 'metadata-orchestration') openMetadataOrchestration();
  if (action.key === 'actions') openModuleActions();
  if (action.key === 'openapi') openModuleOpenApi();
  if (action.key === 'edit') startEdit();
  if (action.key === 'create-child') startCreateChild();
  if (action.key === 'delete') void removeSelected();
  if (action.key === 'cancel') cancelEdit();
  if (action.key === 'save') void save();
}

function openMetadataOrchestration() {
  const module = selectedModule.value;
  const moduleAlias = module?.alias ?? module?.id;
  if (!moduleAlias || module?.moduleKind !== 'dynamic') return;
  workbenchNavigation?.openPage(
    createWorkspaceViewDescriptor(metadataOrchestrationWorkspaceView, {
      moduleAlias,
      moduleTitle: module.title,
    }),
  );
}

function openModuleActions() {
  const moduleAlias = selectedModule.value?.alias ?? selectedModule.value?.id;
  if (!moduleAlias) return;
  workbenchNavigation?.openPage(
    createWorkspaceViewDescriptor(moduleActionManagementWorkspaceView, {
      moduleAlias,
      moduleTitle: selectedModule.value?.title,
      moduleKind: selectedModule.value?.moduleKind,
    }),
  );
}

function openModuleOpenApi() {
  const moduleAlias = selectedModule.value?.alias ?? selectedModule.value?.id;
  if (!moduleAlias) return;
  workbenchNavigation?.openPage(createModuleOpenApiPageDescriptor(moduleAlias, selectedModule.value?.title));
}

function updateDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  draft.value = { ...draft.value, [fieldName]: value };
}

const moduleFormFieldFallback: Record<ModuleFormFieldName, RecordFormFieldFallback> = {
  alias: { label: '模块 alias', required: true, placeholder: '例如 crm.customer' },
  title: { label: '模块名称', required: true, placeholder: '请输入模块名称' },
  parentId: { label: '上级模块', controlType: 'recordPicker', placeholder: '根模块留空' },
  moduleKind: {
    label: '模块类型',
    required: true,
    controlType: 'select',
    options: [
      { label: '静态模块', value: 'static' },
      { label: '动态模块', value: 'dynamic' },
    ],
  },
  entryType: {
    label: '入口类型',
    required: true,
    controlType: 'select',
    options: [
      { label: '模块入口', value: 'module' },
      { label: '内部路由', value: 'route' },
      { label: '外部链接', value: 'link' },
    ],
  },
  entryRoute: { label: '内部路由', placeholder: '例如 /crm/customers' },
  entryExternalUrl: { label: '外部链接', placeholder: '请输入 https:// 开头的地址' },
  enabled: { label: '启用状态', controlType: 'enabledStatus' },
};
</script>

<template>
  <ManagementWorkspace class="module-management-workspace" :explorer-count="2">
    <RecordExplorerPanel
      v-model:search-keyword="applicationSearchKeyword"
      class="application-column"
      title="应用列表"
      search-placeholder="搜索应用名称或 alias"
      @refresh="applicationReloadKey += 1"
    >
      <CrudRecordListExplorer
        :context="applicationContext"
        :selected-id="selectedApplicationAlias"
        :reload-key="applicationReloadKey"
        :keyword="applicationSearchKeyword"
        empty-description="暂无可用应用"
        loading-tip="加载应用列表"
        fallback-title="未命名应用"
        :item-of="applicationItemOf"
        @loaded="loadApplications"
        @select="selectApplication"
      />
    </RecordExplorerPanel>

    <RecordExplorerPanel
      v-model:search-keyword="moduleSearchKeyword"
      class="module-tree-column"
      title="模块树"
      search-placeholder="搜索模块名称或 alias"
      @refresh="moduleReloadKey += 1"
    >
      <template #actions>
        <ModuleActionButton
          class="record-panel-create-button"
          :context="moduleContext"
          action-code="create"
          title="新建模块"
          icon-only
          :disabled="!selectedApplicationAlias || saving || !canCreate"
          @click="startCreateRoot"
        />
      </template>
      <UiEmpty v-if="applications.length === 0" description="暂无可用应用" />
      <UiEmpty v-else-if="!selectedApplicationAlias" description="请选择应用" />
      <TreeRecordExplorer
        v-else
        :context="moduleTreeContext"
        :selected-id="selectedModule?.id"
        :reload-key="moduleReloadKey"
        :keyword="moduleSearchKeyword"
        search-mode="none"
        empty-description="当前应用暂无模块"
        loading-tip="加载模块树"
        fallback-title="未命名模块"
        :item-of="moduleItemOf"
        @loaded="handleModulesLoaded"
        @select="selectModule"
        @action="handleModuleTreeAction"
      />
    </RecordExplorerPanel>

    <RecordDetailPanel class="module-detail-column" :title="cardTitle">
      <template #status>
        <RecordStatusSwitch
          v-if="mode === 'view' && selectedModule"
          :enabled="selectedModule.enabled"
          :disabled="saving || !canToggle"
          :loading="saving"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template #actions>
        <RecordActionBar :context="moduleContext" :actions="moduleActions" @action="handleModuleAction" />
      </template>
      <UiEmpty v-if="!selectedModule && mode === 'view'" description="请选择或新建模块" />
      <form v-else class="module-form" @submit.prevent="save">
        <label>
          <span>所属应用</span>
          <UiInput :value="selectedApplicationAlias" disabled />
        </label>
        <RecordFormFields
          :record="draft as RecordFormRecord"
          :field-names="moduleFormFieldNames"
          :fields="moduleFormFieldDefinitions"
          :fallback="moduleFormFieldFallback"
          :picker-configs="modulePickerConfigs"
          :disabled="formDisabled"
          :disabled-of="(fieldName: string) => fieldName === 'alias' && aliasReadonly"
          @update:field="updateDraftField"
        />
      </form>
      <RecordMetaSection v-if="selectedModule || mode !== 'view'" :record="draft" show-sort-order />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>

<style scoped>
.module-management-workspace {
  grid-template-columns: minmax(220px, 260px) minmax(260px, 320px) minmax(560px, 1fr);
}

.application-column,
.module-tree-column {
  min-width: 0;
  min-height: 0;
}

.module-detail-column {
  min-width: 0;
  min-height: 0;
}

.record-panel-create-button {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.module-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.module-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}

@media (max-width: 980px) {
  .module-management-workspace {
    height: auto;
    overflow: visible;
  }

  .module-management-workspace,
  .module-form {
    grid-template-columns: 1fr;
  }
}
</style>
