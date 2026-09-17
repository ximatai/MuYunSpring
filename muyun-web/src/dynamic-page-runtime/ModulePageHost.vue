<script setup lang="ts">
import { computed, ref } from 'vue';
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import type { QueryListRecord } from '@muyun/platform-components';
import ModulePageHostSession from './ModulePageHostSession.vue';

defineOptions({ name: 'ModulePageHost' });
const props = defineProps<{
  descriptor: StandardModulePageDescriptor;
  requireConfiguredPage?: boolean;
  recordOnly?: { recordId: string; renderMode?: 'inline' | 'portal'; scope?: 'tab' | 'viewport' };
  /** Rebuilds the published business definition without recreating tenant controls. */
  reloadKey?: number;
}>();
const emit = defineEmits<{
  'interaction-state-change': [state: { editing: boolean; busy: boolean }];
  'record-only-change': [mutation: { type: 'saved' | 'deleted' | 'unavailable'; record?: QueryListRecord }];
  'record-only-close': [];
}>();
const session = ref<InstanceType<typeof ModulePageHostSession>>();
// Params are business input for one entry. Only an entry/record target change
// reconstructs the control context and its tenant policy.
const controlSessionKey = computed(() =>
  JSON.stringify({
    moduleAlias: props.descriptor.target.moduleAlias,
    menuId: props.descriptor.menuId,
    pageMode: props.descriptor.target.pageMode,
    recordOnly: props.recordOnly
      ? {
          recordId: props.recordOnly.recordId,
        }
      : undefined,
  }),
);
function refreshList() {
  session.value?.refreshList();
}
defineExpose({ refreshList });
</script>

<template>
  <ModulePageHostSession
    :key="controlSessionKey"
    ref="session"
    :descriptor="descriptor"
    :require-configured-page="requireConfiguredPage"
    :record-only="recordOnly"
    :reload-key="reloadKey"
    @interaction-state-change="emit('interaction-state-change', $event)"
    @record-only-change="emit('record-only-change', $event)"
    @record-only-close="emit('record-only-close')"
  />
</template>
