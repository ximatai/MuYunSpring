<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  createScopedTreeModuleContext,
  ManagementExplorerColumn,
  ManagementWorkspace,
  ModuleActionButton,
  RecordActionBar,
  RecordDetailPanel,
  RecordExplorerPanel,
  parentRecordConstraints,
  RecordMetaSection,
  RecordPicker,
  RecordStatusSwitch,
  TreeRecordExplorer,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordPickerRecord,
  type TreeRecordBase,
  presentPlatformMessage,
} from '@muyun/platform-components';
import type { Organization, Tenant } from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { confirmAction, UiEmpty, UiInput } from '@muyun/vue-ui-antdv';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { createOrganizationManagementState } from './organizationManagementState';

defineOptions({ name: 'OrganizationManagementView' });

const organizationContext = useModuleContext<Organization>();
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const currentUser = useCurrentUserContext();
const explorerSearchKeyword = ref('');
const tenantSearchKeyword = ref('');
const tenantReloadKey = ref(0);
const selectedTenant = ref<Tenant>();
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const selectedTenantId = computed(() => selectedTenant.value?.id);
const organizationScopeOptions = {
  scopeFieldName: 'tenantId',
  scopeValue: () => selectedTenantId.value,
  treePath: '/iam.organization/tree',
};
const scopedOrganizationContext = createScopedTreeModuleContext(
  organizationContext,
  organizationScopeOptions,
) as ModuleContext<Organization>;
const organizationCrud = {
  ...scopedOrganizationContext.crud,
  insert: (record: Organization) =>
    scopedOrganizationContext.crud.insert({ ...record, tenantId: selectedTenantId.value }),
  update: (id: string, record: Organization) => scopedOrganizationContext.crud.update(id, record),
};
const organizationManagementContext: ModuleContext<Organization> = {
  ...scopedOrganizationContext,
  crud: organizationCrud,
  abilities: {
    ...scopedOrganizationContext.abilities,
    crud: () => organizationCrud,
  },
};
const {
  selected,
  draft,
  mode,
  reloadKey,
  saving,
  cardTitle,
  readonly,
  canEnable,
  handleTreeLoaded,
  handleSelect,
  startCreateRoot,
  startCreateChild,
  startEdit,
  cancelEdit,
  save,
  toggleEnabled,
  removeSelected,
} = createOrganizationManagementState(organizationManagementContext, confirmAction);

onMounted(() => {
  if (!canBrowseTenants.value && currentUser?.value?.tenantId) {
    selectedTenant.value = { id: currentUser.value.tenantId, title: currentUser.value.tenantId } as Tenant;
  }
});

watch(selectedTenantId, () => {
  handleTreeLoaded([]);
  reloadKey.value += 1;
});

const cardActions = computed<RecordActionItem[]>(() => {
  if (mode.value !== 'view') {
    return [
      { key: 'cancel', title: '取消', disabled: saving.value },
      {
        key: 'save',
        actionCode: mode.value === 'create' ? 'create' : 'update',
        title: saving.value ? '保存中' : '保存',
        loading: saving.value,
        primary: true,
      },
    ];
  }
  return [
    { key: 'edit', actionCode: 'update', title: '编辑', disabled: !selected.value },
    { key: 'create-child', actionCode: 'create', title: '新建下级', disabled: !selected.value },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      disabled: !selected.value,
      loading: saving.value,
      danger: true,
    },
  ];
});

function organizationTitle(record: RecordPickerRecord) {
  return record.title ?? record.code ?? record.id ?? '未命名机构';
}

function organizationItemOf(record: TreeRecordBase): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.code ?? record.id ?? '未命名机构',
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
  };
}

function selectTenant(tenant: Tenant) {
  selectedTenant.value = tenant;
}

function tenantItemOf(record: Tenant): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名租户',
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}

function startCreateRootScoped() {
  if (!selectedTenantId.value) {
    presentPlatformMessage('请先选择租户', { source: 'organization-management', phase: 'validation' });
    return;
  }
  startCreateRoot();
}

function handleCardAction(action: RecordActionItem) {
  if (action.key === 'edit') {
    startEdit();
    return;
  }
  if (action.key === 'create-child') {
    startCreateChild();
    return;
  }
  if (action.key === 'delete') {
    void removeSelected();
    return;
  }
  if (action.key === 'cancel') {
    cancelEdit();
    return;
  }
  if (action.key === 'save') {
    void save();
  }
}
</script>

<template>
  <ManagementWorkspace class="organization-management-page" :explorer-count="canBrowseTenants ? 2 : 1">
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
        v-model:search-keyword="explorerSearchKeyword"
        title="机构树"
        refresh-title="刷新机构树"
        search-placeholder="搜索机构名称、编码或 ID"
        @refresh="reloadKey += 1"
      >
        <template #actions>
          <ModuleActionButton
            class="record-panel-create-button"
            :context="organizationManagementContext"
            action-code="create"
            title="新建根机构"
            icon-only
            :disabled="!selectedTenantId"
            @click="startCreateRootScoped"
          />
        </template>
        <TreeRecordExplorer
          :context="organizationManagementContext"
          :selected-id="selected?.id"
          :reload-key="reloadKey"
          :keyword="explorerSearchKeyword"
          search-mode="none"
          search-placeholder="搜索机构名称、编码或 ID"
          :empty-description="selectedTenantId ? '暂无机构' : '请选择租户'"
          :loading-tip="selectedTenantId ? '加载机构树' : '等待选择租户'"
          fallback-title="未命名机构"
          :item-of="organizationItemOf"
          @select="handleSelect"
          @loaded="handleTreeLoaded($event as Organization[])"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>
    <RecordDetailPanel :title="cardTitle">
      <template #actions>
        <RecordActionBar
          :context="organizationManagementContext"
          :actions="cardActions"
          @action="handleCardAction"
        />
      </template>
      <template #status>
        <RecordStatusSwitch
          v-if="mode !== 'view'"
          :enabled="draft.enabled"
          :show-label="false"
          @change="draft.enabled = $event"
        />
        <RecordStatusSwitch
          v-else-if="selected"
          :enabled="selected.enabled"
          :disabled="saving || !canEnable"
          :loading="saving"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>

      <UiEmpty
        v-if="!selected && mode === 'view'"
        :description="selectedTenantId ? '请选择或新建机构' : '请选择租户或新建机构'"
      />
      <form v-else class="static-record-form" @submit.prevent="save">
        <label>
          <span>机构名称</span>
          <UiInput v-model:value="draft.title" :disabled="readonly" />
        </label>
        <label>
          <span>机构编码</span>
          <UiInput v-model:value="draft.code" :disabled="readonly" />
        </label>
        <label>
          <span>上级机构</span>
          <RecordPicker
            v-model:value="draft.parentId"
            :context="organizationManagementContext"
            :disabled="readonly"
            :constraints="parentRecordConstraints(draft.id)"
            :title-of="organizationTitle"
            placeholder="根机构留空"
          />
        </label>
      </form>

      <RecordMetaSection :record="draft" />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>
