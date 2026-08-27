<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  RecordDetailPanel,
  RecordExplorerPanel,
  RecordTreeSelector,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
} from '@muyun/platform-components';
import {
  UiButton,
  UiCheckbox,
  UiDataTable,
  UiEmpty,
  UiError,
  UiSelect,
  UiSpin,
  UiSwitch,
  confirmAction,
} from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord } from '@muyun/vue-ui-antdv';
import type {
  DataScopePolicy,
  Role,
  RoleAuthorizationModule,
  RoleDataGrantActionMatrix,
  RoleDataScopePolicyCatalog,
  RolePermissionAction,
} from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import WorkspaceViewDrawer from '../platform-admin-runtime/WorkspaceViewDrawer.vue';
import { useWorkspaceViewHost } from '../platform-admin-runtime/workspaceViewHost';
import { useWorkspaceViewPromotion } from '../platform-admin-runtime/useWorkspaceViewPromotion';
import { createRoleGrantClient } from './roleGrantClient';
import { roleAuthorizationWorkspaceView } from './roleAuthorizationWorkspaceView';
import {
  handOffRoleAuthorizationWorkspaceSession,
  registerRoleAuthorizationWorkspaceHandoffRecipient,
  takeRoleAuthorizationWorkspaceSession,
} from './roleAuthorizationWorkspaceSession';

defineOptions({ name: 'RoleAuthorizationView' });

const props = defineProps<{
  roleId?: string;
  /** Reuses a host-governed module context when this view is mounted as an extension surface. */
  moduleContext?: ModuleContext<Role>;
  /** Parent page root required when this view is displayed inside a drawer. */
  container?: HTMLElement | null;
  /** A parent management tab owns the initial wide drawer before promotion. */
  drawer?: boolean;
  /** The action drawer owns the outer title and fixed operation region. */
  drawerContext?: ModulePageDrawerContext;
}>();
const emit = defineEmits<{ close: [] }>();

const defaultRoleContext = useModuleContext<Role>({ moduleAlias: 'iam.role' });
const roleContext = props.moduleContext ?? defaultRoleContext;
const client = createRoleGrantClient(roleContext.http);
const roleId = props.roleId ?? new URLSearchParams(window.location.search).get('roleId') ?? '';
const workspaceViewHost = useWorkspaceViewHost();
const workspaceInput = props.roleId ? { roleId: props.roleId } : undefined;
const restoredWorkspaceSession = workspaceInput
  ? takeRoleAuthorizationWorkspaceSession(workspaceInput)
  : undefined;
const role = ref<Role>();
const modules = ref<RoleAuthorizationModule[]>([]);
const selectedModuleAlias = ref<string>();
const moduleKeyword = ref('');
const actions = ref<RolePermissionAction[]>([]);
const dataGrantMatrix = ref<RoleDataGrantActionMatrix>();
const dataScopeCatalog = ref<RoleDataScopePolicyCatalog>();
const loading = ref(false);
const loadingActions = ref(false);
const saving = ref(false);
const error = ref<string>();
const savedDataGrantSnapshot = ref<string>();
const actionDrafts = new Map<string, RolePermissionAction[]>();
const actionSnapshots = new Map<string, string>();
const actionDraftVersion = ref(0);
let disposeWorkspaceHandoffRecipient: (() => void) | undefined;

const isGroup = computed(() => role.value?.roleKind === 'group');
const isDataGrant = computed(() => role.value?.roleKind === 'dataGrant');
const isEmploymentRole = computed(() => role.value?.assignmentType === 'employment');
const isActionDrawerSurface = computed(() => Boolean(props.drawerContext));
const roleTitle = computed(() => role.value?.title ?? roleId ?? '角色');
const workspaceTitle = computed(() => `授权：${roleTitle.value}`);
const actionPanelTitle = computed(() =>
  selectedModule.value ? `动作授权 - ${selectedModule.value.title}` : `动作授权 - ${roleTitle.value}`,
);
const selectedModule = computed(() =>
  modules.value.find((item) => item.moduleAlias === selectedModuleAlias.value),
);
const moduleTreeRecords = computed(() => {
  const keyword = moduleKeyword.value.trim().toLocaleLowerCase();
  const visibleAliases = new Set(
    keyword
      ? modules.value
          .filter((module) =>
            [module.title, module.moduleAlias, module.applicationAlias]
              .filter((value): value is string => Boolean(value))
              .some((value) => value.toLocaleLowerCase().includes(keyword)),
          )
          .map((module) => module.moduleAlias)
      : modules.value.map((module) => module.moduleAlias),
  );
  const byAlias = new Map(modules.value.map((module) => [module.moduleAlias, module]));
  for (const moduleAlias of [...visibleAliases]) {
    let parentId = byAlias.get(moduleAlias)?.parentId;
    while (parentId && byAlias.has(parentId) && !visibleAliases.has(parentId)) {
      visibleAliases.add(parentId);
      parentId = byAlias.get(parentId)?.parentId;
    }
  }
  return modules.value.filter((module) => visibleAliases.has(module.moduleAlias)).map((module) => ({
    id: module.moduleAlias,
    parentId: module.parentId,
    title: module.title,
    secondary: module.applicationAlias,
  }));
});
const scopeOptions = computed(() =>
  (dataScopeCatalog.value?.options ?? []).map((option) => ({ value: option.code, label: option.title })),
);
const referenceDependencyOptions = computed(() =>
  (dataScopeCatalog.value?.referenceDependencies ?? []).map((dependency) => ({
    value: dependency.referenceFieldId,
    label: `${dependency.title} → ${dependency.targetModuleTitle}`,
  })),
);
const referenceDependencyByField = computed(
  () =>
    new Map(
      (dataScopeCatalog.value?.referenceDependencies ?? []).map((item) => [item.referenceFieldId, item]),
    ),
);
const supportsDataScope = computed(
  () => isEmploymentRole.value && actions.value.some((action) => Boolean(action.dataAuth)),
);
const actionColumns = computed<UiDataTableColumn[]>(() => [
  { key: 'title', title: '动作', width: 160 },
  { key: 'granted', title: '授权', width: 104, align: 'center' },
  ...(supportsDataScope.value ? [{ key: 'dataScopePolicy', title: '数据范围', width: 200 }] : []),
]);
const dataGrantColumns: UiDataTableColumn[] = [
  { key: 'title', title: '标准动作', width: 130 },
  { key: 'configured', title: '启用模板', width: 104, align: 'center' },
  { key: 'dataScopePolicy', title: '数据范围', width: 160 },
];
const actionRows = computed(() => actions.value as unknown as UiDataTableRecord[]);
const dataGrantRows = computed(
  () => (dataGrantMatrix.value?.actions ?? []) as unknown as UiDataTableRecord[],
);
const isDrawerWorkspaceView = computed(
  () => props.drawer === true || (Boolean(workspaceInput) && workspaceViewHost?.presentation === 'drawer'),
);
const isWorkspaceView = computed(() => Boolean(workspaceInput));
const usesStandaloneWorkspaceShell = computed(() => isWorkspaceView.value && !isActionDrawerSurface.value);
const workspaceContainer = computed(() =>
  isActionDrawerSurface.value
    ? 'div'
    : isDrawerWorkspaceView.value
      ? WorkspaceViewDrawer
      : isWorkspaceView.value
        ? RecordDetailPanel
        : 'div',
);
const workspaceContainerProps = computed(() =>
  isDrawerWorkspaceView.value
    ? {
        open: true,
        title: workspaceTitle.value,
        container: props.container ?? null,
        profile: roleAuthorizationWorkspaceView.drawerProfile,
        promotion: authorizationPromotion.value,
        onClose: dismissWorkspaceView,
      }
    : usesStandaloneWorkspaceShell.value
      ? { title: workspaceTitle.value }
      : {},
);
const dataGrantDirty = computed(() => {
  const matrix = dataGrantMatrix.value;
  if (!matrix || savedDataGrantSnapshot.value === undefined) return false;
  return JSON.stringify(matrix.actions) !== savedDataGrantSnapshot.value;
});
const permissionMatrixDirty = computed(() => {
  return (
    actionDraftVersion.value >= 0 &&
    [...actionDrafts.entries()].some(
      ([moduleAlias, draft]) => actionSnapshots.get(moduleAlias) !== JSON.stringify(draft),
    )
  );
});
const authorizationDirty = computed(() => dataGrantDirty.value || permissionMatrixDirty.value);
const allActionsGranted = computed(
  () => actions.value.length > 0 && actions.value.every((action) => action.granted),
);
const someActionsGranted = computed(
  () => actions.value.some((action) => action.granted) && !allActionsGranted.value,
);
const actionOperationSummary = computed(() => {
  const moduleTitle = selectedModule.value?.title ?? '未选择模块';
  const grantedCount = actions.value.filter((action) => action.granted).length;
  const changes = authorizationDirty.value ? '有未确认修改' : '未修改';
  return `当前模块：${moduleTitle} · 已授权 ${grantedCount}/${actions.value.length} 项 · ${changes}`;
});
const authorizationPromotion = useWorkspaceViewPromotion({
  view: roleAuthorizationWorkspaceView,
  input: () => workspaceInput,
  title: workspaceTitle,
  eligibility: () => ({ hasStableIdentity: Boolean(roleId && role.value), busy: saving.value }),
  beforePromote: (input) =>
    handOffRoleAuthorizationWorkspaceSession(input, {
      selectedModuleAlias: selectedModuleAlias.value,
      dataGrantMatrix: dataGrantMatrix.value,
      actionDrafts: [...actionDrafts.entries()].map(([moduleAlias, draft]) => ({
        moduleAlias,
        actions: draft,
      })),
      actionSnapshots: [...actionSnapshots.entries()].map(([moduleAlias, snapshot]) => ({
        moduleAlias,
        snapshot,
      })),
    }).then((result) => result === 'accepted'),
  // A successful hand-off, including a reused target tab, always completes
  // the host migration and closes the source drawer.
  onPromoted: dismissPromotedDrawer,
  onPromotionRejected: () =>
    presentPlatformMessage('目标页签存在未确认的授权配置；请先确认或关闭该页签后再固定当前抽屉。', {
      source: 'role-authorization',
      phase: 'validation',
    }),
});

onMounted(() => {
  if (workspaceInput && !isDrawerWorkspaceView.value) {
    disposeWorkspaceHandoffRecipient = registerRoleAuthorizationWorkspaceHandoffRecipient(
      workspaceInput,
      receiveWorkspaceHandoff,
    );
  }
  void load();
});
watch(
  workspaceTitle,
  (title) => {
    if (isWorkspaceView.value) workspaceViewHost?.setTitle(title);
  },
  { immediate: true },
);
watch(selectedModuleAlias, () => void loadActions());
watch(
  [roleTitle, actionOperationSummary, authorizationDirty, saving, isGroup, isDataGrant],
  configureActionDrawerPresentation,
  { immediate: true },
);

onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload));
onBeforeUnmount(() => {
  disposeWorkspaceHandoffRecipient?.();
  props.drawerContext?.setOperation(undefined);
  props.drawerContext?.setSubtitle(undefined);
  window.removeEventListener('beforeunload', warnBeforeUnload);
});

function configureActionDrawerPresentation() {
  const drawer = props.drawerContext;
  if (!drawer) return;
  drawer.setSubtitle(`${roleTitle.value} · ${scopeTitle(role.value)}`);
  if (isGroup.value) {
    drawer.setOperation(undefined);
    return;
  }
  drawer.setOperation({
    summary: isDataGrant.value ? (authorizationDirty.value ? '有未确认修改' : '未修改') : actionOperationSummary.value,
    actions: [
      {
        key: 'confirm-role-authorization',
        label: '确认',
        emphasis: 'primary',
        disabled: saving.value || !authorizationDirty.value,
        loading: saving.value,
        run: () => void confirmAuthorization(),
      },
    ],
  });
}

async function load() {
  if (!roleId) {
    error.value = '缺少角色标识，无法打开授权页。';
    return;
  }
  loading.value = true;
  error.value = undefined;
  try {
    role.value = await roleContext.crud.view(roleId);
    if (role.value.roleKind === 'group') return;
    if (role.value.roleKind === 'dataGrant') {
      const [matrix, catalog] = await Promise.all([
        client.dataGrantActionMatrix(roleId),
        client.dataScopePolicyCatalog(roleId),
      ]);
      dataGrantMatrix.value = restoredWorkspaceSession?.dataGrantMatrix ?? matrix;
      dataScopeCatalog.value = catalog;
      savedDataGrantSnapshot.value = JSON.stringify(matrix.actions);
      return;
    }
    modules.value = (await client.authorizationModules(roleId)).records;
    selectedModuleAlias.value =
      restoredWorkspaceSession?.selectedModuleAlias ?? modules.value[0]?.moduleAlias;
  } catch (cause) {
    error.value = '授权信息加载失败，请重试。';
    presentPlatformError(cause, { source: 'role-authorization', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadActions() {
  const moduleAlias = selectedModuleAlias.value;
  if (!moduleAlias || isDataGrant.value || isGroup.value) return;
  loadingActions.value = true;
  try {
    const [matrix, catalog] = await Promise.all([
      client.permissionMatrix(roleId, [moduleAlias]),
      client.dataScopePolicyCatalog(roleId, moduleAlias),
    ]);
    const fetchedActions = (matrix.modules[0]?.actions ?? []).map(normalizeEmploymentDataScope);
    const restoredDraft = restoredWorkspaceSession?.actionDrafts?.find(
      (draft) => draft.moduleAlias === moduleAlias,
    )?.actions;
    const draft = actionDrafts.get(moduleAlias) ?? restoredDraft;
    actions.value = draft ?? fetchedActions;
    if (!actionDrafts.has(moduleAlias)) {
      actionDrafts.set(moduleAlias, actions.value);
      actionSnapshots.set(
        moduleAlias,
        restoredWorkspaceSession?.actionSnapshots?.find((snapshot) => snapshot.moduleAlias === moduleAlias)
          ?.snapshot ?? JSON.stringify(fetchedActions),
      );
    }
    actionDraftVersion.value += 1;
    dataScopeCatalog.value = catalog;
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-authorization', phase: 'load' });
  } finally {
    loadingActions.value = false;
  }
}

function updateAction(action: RolePermissionAction, granted: boolean) {
  if (granted && action.dataAuth && isEmploymentRole.value && !action.dataScopePolicy) {
    action.dataScopePolicy = 'inheritDataGrant';
  }
  if (granted && action.dataScopePolicy === 'referenceDependency' && !action.referenceFieldId) {
    action.dataScopePolicy = 'inheritDataGrant';
  }
  action.granted = granted;
  actionDraftVersion.value += 1;
}

function updateAllActions(granted: boolean) {
  actions.value.forEach((action) => updateAction(action, granted));
}

function updateActionScope(action: RolePermissionAction, value: unknown) {
  action.dataScopePolicy = String(value || 'none') as DataScopePolicy;
  if (action.dataScopePolicy === 'referenceDependency') {
    action.referenceFieldId = undefined;
    action.referenceActionCode = undefined;
    actionDraftVersion.value += 1;
    return;
  }
  action.referenceFieldId = undefined;
  action.referenceActionCode = undefined;
  actionDraftVersion.value += 1;
}

function updateReferenceDependency(action: RolePermissionAction, referenceFieldId: unknown) {
  const dependency = referenceDependencyByField.value.get(String(referenceFieldId || ''));
  action.referenceFieldId = dependency?.referenceFieldId;
  action.referenceActionCode = dependency?.referenceActionCode;
  actionDraftVersion.value += 1;
}

function referenceDependencyOf(referenceFieldId: unknown) {
  return referenceDependencyByField.value.get(String(referenceFieldId || ''));
}

function selectValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? value : undefined;
}

function displayedDataScopePolicy(action: RolePermissionAction): DataScopePolicy {
  return action.dataScopePolicy ?? 'inheritDataGrant';
}

function isEmploymentDataScopeAction(record: UiDataTableRecord) {
  return Boolean(record.dataAuth) && isEmploymentRole.value;
}

function isEmploymentDataScopeColumn(column: UiDataTableColumn, record: UiDataTableRecord) {
  return column.key === 'dataScopePolicy' && isEmploymentDataScopeAction(record);
}

function normalizeEmploymentDataScope(action: RolePermissionAction): RolePermissionAction {
  if (isEmploymentRole.value && action.dataAuth && action.dataScopePolicy === 'none') {
    return { ...action, dataScopePolicy: 'inheritDataGrant' };
  }
  return action;
}

async function saveDataGrantMatrix() {
  if (!dataGrantMatrix.value) return;
  saving.value = true;
  try {
    const result = await client.replaceDataGrantActions(
      roleId,
      dataGrantMatrix.value.actions.map((action) => ({
        actionCode: action.actionCode,
        dataScopePolicy: action.dataScopePolicy,
        enabled: action.configured,
      })),
    );
    await handlePlatformActionSuccess(result, {
      source: 'role-authorization',
      phase: 'action',
      fallbackMessage: '数据权限模板已保存',
    });
    savedDataGrantSnapshot.value = JSON.stringify(dataGrantMatrix.value.actions);
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-authorization', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function savePermissionMatrix() {
  const dirtyDrafts = [...actionDrafts.entries()].filter(
    ([moduleAlias, draft]) => actionSnapshots.get(moduleAlias) !== JSON.stringify(draft),
  );
  if (!dirtyDrafts.length) return;
  saving.value = true;
  try {
    const result = await client.replacePermissionMatrix(
      roleId,
      dirtyDrafts.flatMap(([moduleAlias, draft]) =>
        draft.map((action) => ({
          moduleAlias,
          actionCode: action.actionCode,
          granted: Boolean(action.granted),
          dataScopePolicy:
            action.granted && action.dataAuth && isEmploymentRole.value
              ? (action.dataScopePolicy ?? 'inheritDataGrant')
              : undefined,
          referenceFieldId:
            action.granted && action.dataScopePolicy === 'referenceDependency'
              ? action.referenceFieldId
              : undefined,
          referenceActionCode:
            action.granted && action.dataScopePolicy === 'referenceDependency'
              ? action.referenceActionCode
              : undefined,
        })),
      ),
    );
    await handlePlatformActionSuccess(result, {
      source: 'role-authorization',
      phase: 'action',
      fallbackMessage: '角色授权已保存',
    });
    dirtyDrafts.forEach(([moduleAlias, draft]) => actionSnapshots.set(moduleAlias, JSON.stringify(draft)));
    actionDraftVersion.value += 1;
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-authorization', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function confirmAuthorization() {
  if (isDataGrant.value) {
    await saveDataGrantMatrix();
    return;
  }
  await savePermissionMatrix();
}

async function dismissWorkspaceView() {
  if (authorizationDirty.value) {
    const confirmed = await confirmAction({
      title: '放弃未确认的授权配置',
      content: '当前修改尚未确认，关闭后将丢失这些修改。',
      okText: '放弃修改',
      danger: true,
    });
    if (!confirmed) return;
  }
  dismissDrawerHost();
}

function dismissPromotedDrawer() {
  // The hand-off session already contains the draft, so this is intentionally
  // distinct from a user-requested close and skips discard confirmation.
  dismissDrawerHost();
}

function receiveWorkspaceHandoff(
  session: import('./roleAuthorizationWorkspaceSession').RoleAuthorizationWorkspaceSession,
) {
  if (authorizationDirty.value) return false;
  selectedModuleAlias.value = session.selectedModuleAlias;
  dataGrantMatrix.value = session.dataGrantMatrix;
  actionDrafts.clear();
  actionSnapshots.clear();
  session.actionDrafts?.forEach(({ moduleAlias, actions: draft }) => actionDrafts.set(moduleAlias, draft));
  session.actionSnapshots?.forEach(({ moduleAlias, snapshot }) => actionSnapshots.set(moduleAlias, snapshot));
  actionDraftVersion.value += 1;
  return true;
}

function dismissDrawerHost() {
  if (props.drawer === true) {
    emit('close');
    return;
  }
  workspaceViewHost?.dismiss();
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!authorizationDirty.value) return;
  event.preventDefault();
  event.returnValue = '';
}

function scopeTitle(value: Role | undefined) {
  if (value?.ownerScopeType === 'platform') return '平台范围';
  if (value?.ownerScopeType === 'organization') return '机构范围';
  return '租户范围';
}
</script>

<template>
  <component :is="workspaceContainer" v-bind="workspaceContainerProps">
    <template v-if="usesStandaloneWorkspaceShell && !isGroup" #operation>
      <UiButton
        type="primary"
        :disabled="!authorizationDirty"
        :loading="saving"
        @click="confirmAuthorization"
      >
        确认
      </UiButton>
    </template>
    <section
      :class="[
        'role-authorization-page',
        { 'role-authorization-page--drawer': isDrawerWorkspaceView || isActionDrawerSurface },
      ]"
    >
      <UiSpin v-if="loading" tip="加载授权信息" />
      <UiError v-else-if="error" title="授权页加载失败" :message="error" />
      <UiEmpty
        v-else-if="isGroup"
        title="角色组不独立授权"
        description="角色组只组合成员角色；请到成员角色分别配置动作和数据权限。"
      />
      <RecordExplorerPanel
        v-else-if="isDataGrant && dataGrantMatrix"
        class="data-grant-content"
        :title="usesStandaloneWorkspaceShell ? '数据权限模板' : `数据权限模板 - ${roleTitle}`"
        :searchable="false"
        @refresh="load"
      >
        <header class="panel-title">
          <div>
            <h3>标准动作的数据范围模板</h3>
            <p>模板只会在具体任职的普通角色动作选择“继承数据授权角色”时生效。</p>
          </div>
          <UiButton
            v-if="!isWorkspaceView"
            type="primary"
            :disabled="!authorizationDirty"
            :loading="saving"
            @click="confirmAuthorization"
          >
            确认
          </UiButton>
        </header>
        <UiDataTable :columns="dataGrantColumns" :rows="dataGrantRows" row-key="actionCode" horizontal-scroll>
          <template #cell="{ column, record }">
            <template v-if="column.key === 'title'">
              <strong>{{ record.title || record.actionCode }}</strong>
              <small>{{ record.actionCode }}</small>
            </template>
            <UiSwitch
              v-else-if="column.key === 'configured'"
              :checked="Boolean(record.configured)"
              :disabled="saving"
              @change="record.configured = $event"
            />
            <UiSelect
              v-else
              :value="record.dataScopePolicy as DataScopePolicy"
              :options="scopeOptions"
              :disabled="!record.configured || saving"
              placeholder="请选择数据范围"
              :allow-clear="false"
              @update:value="record.dataScopePolicy = String($event || '')"
            />
          </template>
        </UiDataTable>
      </RecordExplorerPanel>
      <section
        v-else
        :class="['authorization-layout', { 'authorization-layout--compact-actions': !supportsDataScope }]"
      >
        <RecordExplorerPanel
          class="module-panel"
          title="模块树"
          refresh-title="刷新模块目录"
          v-model:search-keyword="moduleKeyword"
          search-placeholder="搜索模块名称或别名"
          @refresh="load"
        >
          <RecordTreeSelector
            :records="moduleTreeRecords"
            :selected-id="selectedModuleAlias"
            @select="selectedModuleAlias = $event.id"
          />
        </RecordExplorerPanel>
        <RecordExplorerPanel class="action-panel" :title="actionPanelTitle" :searchable="false">
          <template v-if="!isWorkspaceView" #actions>
            <UiButton
              type="primary"
              :disabled="!authorizationDirty"
              :loading="saving"
              @click="confirmAuthorization"
            >
              确认
            </UiButton>
          </template>
          <UiSpin v-if="loadingActions" tip="加载模块动作" />
          <UiEmpty v-else-if="!selectedModuleAlias" title="请选择模块" />
          <UiDataTable
            v-else
            :columns="actionColumns"
            :rows="actionRows"
            row-key="actionCode"
            fill-height
            horizontal-scroll
          >
            <template #header="{ column }">
              <span v-if="column.key === 'granted'" class="authorization-column-header">
                <UiCheckbox
                  :checked="allActionsGranted"
                  :indeterminate="someActionsGranted"
                  :disabled="saving || !actions.length"
                  aria-label="全选当前模块动作授权"
                  @change="updateAllActions"
                />
                授权
              </span>
              <template v-else>{{ column.title }}</template>
            </template>
            <template #cell="{ column, record }">
              <template v-if="column.key === 'title'">
                <strong>{{ record.title || record.actionCode }}</strong>
                <small>{{ record.permissionActionCode || record.actionCode }}</small>
              </template>
              <UiCheckbox
                v-else-if="column.key === 'granted'"
                :checked="Boolean(record.granted)"
                :disabled="saving"
                @change="updateAction(record as unknown as RolePermissionAction, $event)"
              />
              <div
                v-else-if="record.dataAuth && isEmploymentRole && Boolean(record.granted)"
                class="data-scope-editor"
              >
                <UiSelect
                  :value="displayedDataScopePolicy(record as unknown as RolePermissionAction)"
                  :options="scopeOptions"
                  :disabled="saving"
                  :allow-clear="false"
                  @update:value="updateActionScope(record as unknown as RolePermissionAction, $event)"
                />
                <template v-if="record.dataScopePolicy === 'referenceDependency'">
                  <UiSelect
                    :value="selectValue(record.referenceFieldId)"
                    :options="referenceDependencyOptions"
                    :disabled="saving"
                    placeholder="请选择引用字段"
                    :allow-clear="false"
                    @update:value="
                      updateReferenceDependency(record as unknown as RolePermissionAction, $event)
                    "
                  />
                  <small v-if="record.referenceFieldId">
                    依赖目标：{{ referenceDependencyOf(record.referenceFieldId)?.targetModuleTitle }} ·
                    {{ referenceDependencyOf(record.referenceFieldId)?.referenceActionTitle }}
                  </small>
                  <small v-else>请先选择引用字段，再开启该动作授权。</small>
                </template>
              </div>
              <span v-else-if="isEmploymentDataScopeColumn(column, record)">—</span>
              <span v-else class="not-applicable">不适用</span>
            </template>
          </UiDataTable>
        </RecordExplorerPanel>
      </section>
    </section>
  </component>
</template>

<style scoped>
.role-authorization-page {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  gap: 16px;
  height: 100%;
  min-height: 0;
  min-width: 960px;
  overflow: hidden;
}
.role-authorization-page--drawer {
  min-width: 0;
}
.panel-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
h3,
p {
  margin: 0;
}
h3 {
  font-size: 16px;
}
p,
small {
  color: var(--muyun-text-muted);
}
small {
  display: block;
  margin-top: 3px;
}
.authorization-layout {
  display: grid;
  grid-template-columns: var(--muyun-management-explorer-width, 280px) minmax(0, 1fr);
  align-items: stretch;
  gap: 16px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.authorization-layout--compact-actions {
  grid-template-columns: var(--muyun-management-explorer-width, 280px) minmax(360px, 520px);
  justify-content: start;
}
@media (max-width: 960px) {
  .authorization-layout--compact-actions {
    grid-template-columns: minmax(220px, var(--muyun-management-explorer-width, 280px)) minmax(0, 1fr);
  }
}
.module-panel,
.action-panel,
.data-grant-content {
  min-width: 0;
}
.module-panel {
  min-height: 0;
}
.module-panel :deep(.record-explorer-panel-content) {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.module-panel :deep(.record-tree-selector),
.module-panel :deep(.ant-tree) {
  min-height: 0;
}
.module-panel :deep(.ant-tree) {
  overflow-y: auto;
  overscroll-behavior: contain;
}
.action-panel {
  min-height: 0;
}
.action-panel :deep(.record-explorer-panel-content) {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.action-panel :deep(.ui-data-table) {
  flex: 1 1 auto;
  min-height: 0;
}
.action-panel :deep(.ant-table-body) {
  overscroll-behavior: contain;
}
.data-grant-content {
  display: grid;
  gap: 16px;
}
.authorization-table {
  width: 100%;
  border-collapse: collapse;
}
.authorization-table th,
.authorization-table td {
  padding: 11px 12px;
  border-bottom: 1px solid var(--muyun-border-color);
  text-align: left;
  vertical-align: middle;
}
.authorization-table th {
  color: var(--muyun-text-muted);
  font-weight: 500;
  background: var(--muyun-surface-muted);
}
.authorization-table th:nth-child(2),
.authorization-table td:nth-child(2) {
  width: 110px;
}
.authorization-table th:nth-child(3),
.authorization-table td:nth-child(3) {
  width: 260px;
}
.not-applicable {
  color: var(--muyun-text-muted);
}
.authorization-column-header {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.data-scope-editor {
  display: grid;
  gap: 6px;
}
</style>
