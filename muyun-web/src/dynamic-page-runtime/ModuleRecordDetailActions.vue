<script setup lang="ts">
import { computed } from 'vue';
import { RecordActionBar, type RecordActionItem, type QueryListRecord } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { ModulePageRecordActionContribution } from './modulePageEnhancements';

defineOptions({ name: 'ModuleRecordDetailActions' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<QueryListRecord>;
    record?: QueryListRecord;
    mode: 'create' | 'edit' | 'view';
    saving?: boolean;
    detailLoading?: boolean;
    detailLoadFailed?: boolean;
    recycleBinActive?: boolean;
    actions?: ModulePageRecordActionContribution[];
    /** Server-resolved standard action blocks, intentionally separate from frontend extensions. */
    configuredActions?: RecordActionItem[];
    /** Custom record views retain their own operation model. */
    showStandardViewActions?: boolean;
    /** A workspace is a secondary navigation action, not a business operation. */
    workspaceAvailable?: boolean;
    /** Tree cards may add a child from the selected parent. */
    createChildAvailable?: boolean;
    createChildDisabled?: boolean;
  }>(),
  {
    record: undefined,
    saving: false,
    detailLoading: false,
    detailLoadFailed: false,
    recycleBinActive: false,
    actions: () => [],
    configuredActions: () => [],
    showStandardViewActions: true,
    workspaceAvailable: false,
    createChildAvailable: false,
    createChildDisabled: false,
  },
);

const emit = defineEmits<{
  cancel: [];
  save: [];
  edit: [];
  delete: [];
  openWorkspace: [];
  createChild: [];
  detailAction: [action: RecordActionItem];
}>();

const formActive = computed(() => props.mode === 'create' || props.mode === 'edit');
const recordId = computed(() => (props.record?.id == null ? undefined : String(props.record.id)));
const saveAvailable = computed(() => {
  if (!formActive.value || props.recycleBinActive || props.detailLoading || props.detailLoadFailed) {
    return false;
  }
  return props.context.can(props.mode === 'create' ? 'create' : 'update') === true;
});
const viewActionsActive = computed(
  () => props.mode === 'view' && !props.recycleBinActive && props.showStandardViewActions,
);
const headerActions = computed<RecordActionItem[]>(() => {
  if (formActive.value) {
    return [
      { key: '__platform-cancel', title: '取消', actionLevel: 'standard', disabled: props.saving },
      {
        key: '__platform-save',
        title: props.saving ? '保存中' : '保存',
        actionLevel: 'primary',
        loading: props.saving,
        disabled: !saveAvailable.value,
      },
    ];
  }
  if (props.mode !== 'view' || props.recycleBinActive) return [];
  const actions: RecordActionItem[] = [
    ...(props.workspaceAvailable
      ? [
          {
            key: '__platform-workspace',
            title: '在新标签页打开',
            actionLevel: 'secondary' as const,
            iconName: 'open-in-new' as const,
          },
        ]
      : []),
    ...props.actions,
    ...props.configuredActions,
  ];
  if (props.createChildAvailable) {
    actions.push({
      key: '__platform-create-child',
      title: '新建子项',
      actionLevel: 'primary',
      iconName: 'plus',
      disabled: props.createChildDisabled,
    });
  }
  if (viewActionsActive.value) {
    actions.push(
      {
        key: '__platform-edit',
        actionCode: 'update',
        title: '编辑',
        actionLevel: 'standard',
        disabled: !props.record,
      },
      {
        key: '__platform-delete',
        actionCode: 'delete',
        title: '删除',
        actionLevel: 'secondary',
        danger: true,
        loading: props.saving,
        disabled: !props.record,
      },
    );
  }
  return actions;
});

function handleAction(action: RecordActionItem) {
  switch (action.key) {
    case '__platform-cancel':
      emit('cancel');
      return;
    case '__platform-save':
      emit('save');
      return;
    case '__platform-workspace':
      emit('openWorkspace');
      return;
    case '__platform-create-child':
      emit('createChild');
      return;
    case '__platform-edit':
      emit('edit');
      return;
    case '__platform-delete':
      emit('delete');
      return;
    default:
      emit('detailAction', action);
  }
}
</script>

<template>
  <RecordActionBar
    v-if="headerActions.length"
    :context="context"
    :record-id="recordId"
    :actions="headerActions"
    @action="handleAction"
  />
</template>
