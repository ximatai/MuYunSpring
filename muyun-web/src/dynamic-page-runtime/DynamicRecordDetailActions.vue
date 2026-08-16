<script setup lang="ts">
import { computed } from 'vue';
import {
  ModuleActionButton,
  RecordActionBar,
  RecordPanelButton,
  type RecordActionItem,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { ModulePageRecordActionContribution } from './modulePageEnhancements';

defineOptions({ name: 'DynamicRecordDetailActions' });

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
    /** Custom record views retain their own operation model. */
    showStandardViewActions?: boolean;
  }>(),
  {
    record: undefined,
    saving: false,
    detailLoading: false,
    detailLoadFailed: false,
    recycleBinActive: false,
    actions: () => [],
    showStandardViewActions: true,
  },
);

const emit = defineEmits<{
  cancel: [];
  save: [];
  edit: [];
  delete: [];
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
</script>

<template>
  <template v-if="formActive">
    <RecordPanelButton :disabled="saving" @click="emit('cancel')">取消</RecordPanelButton>
    <RecordPanelButton type="primary" :loading="saving" :disabled="!saveAvailable" @click="emit('save')">
      {{ saving ? '保存中' : '保存' }}
    </RecordPanelButton>
  </template>
  <template v-else-if="viewActionsActive">
    <RecordActionBar
      v-if="recordId && actions.length > 0"
      :context="context"
      :record-id="recordId"
      :actions="actions"
      @action="emit('detailAction', $event)"
    />
    <ModuleActionButton :context="context" action-code="update" :disabled="!record" @click="emit('edit')">
      编辑
    </ModuleActionButton>
    <ModuleActionButton
      :context="context"
      action-code="delete"
      :loading="saving"
      danger
      :disabled="!record"
      @click="emit('delete')"
    >
      删除
    </ModuleActionButton>
  </template>
  <RecordActionBar
    v-else-if="mode === 'view' && !recycleBinActive && recordId && actions.length > 0"
    :context="context"
    :record-id="recordId"
    :actions="actions"
    @action="emit('detailAction', $event)"
  />
</template>
