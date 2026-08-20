<script setup lang="ts">
import type { QueryListRecord, RecordActionItem } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import ModuleRecordDetailActions from './ModuleRecordDetailActions.vue';
import type { ModulePageRecordActionContribution } from './modulePageEnhancements';

/**
 * @deprecated Use ModuleRecordDetailActions. Remove after registered extensions
 * and in-repository imports have migrated to the source-neutral component.
 */
defineOptions({ name: 'DynamicRecordDetailActions' });

withDefaults(
  defineProps<{
    context: ModuleContext<QueryListRecord>;
    record?: QueryListRecord;
    mode: 'create' | 'edit' | 'view';
    saving?: boolean;
    detailLoading?: boolean;
    detailLoadFailed?: boolean;
    recycleBinActive?: boolean;
    actions?: ModulePageRecordActionContribution[];
    configuredActions?: RecordActionItem[];
    showStandardViewActions?: boolean;
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
  },
);

const emit = defineEmits<{
  cancel: [];
  save: [];
  edit: [];
  delete: [];
  detailAction: [action: RecordActionItem];
}>();
</script>

<template>
  <ModuleRecordDetailActions
    v-bind="$props"
    @cancel="emit('cancel')"
    @save="emit('save')"
    @edit="emit('edit')"
    @delete="emit('delete')"
    @detail-action="emit('detailAction', $event)"
  />
</template>
