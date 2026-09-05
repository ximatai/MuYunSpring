<script setup lang="ts">
import { ModuleActionButton, RecordPanelButton } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { NavigatorSortViewState } from './composables/useNavigatorRuntime';

defineOptions({ name: 'NavigatorPanelActions' });

withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    title: string;
    sort: NavigatorSortViewState;
    createAvailable: boolean;
    createDisabled?: boolean;
    createDisabledReason?: string;
  }>(),
  {
    createDisabled: false,
    createDisabledReason: undefined,
  },
);

const emit = defineEmits<{
  create: [];
  'toggle-sorting': [];
}>();
</script>

<template>
  <RecordPanelButton
    v-if="sort.visible"
    icon-name="swap-vertical"
    icon-only
    size="small"
    type="text"
    :selected="sort.active"
    :disabled="!sort.enabled"
    :title="sort.disabledReason ?? (sort.active ? '结束排序' : '调整排序')"
    :aria-label="sort.active ? '结束排序' : '调整排序'"
    @click="emit('toggle-sorting')"
  />
  <ModuleActionButton
    v-if="createAvailable"
    :context="context"
    action-code="create"
    icon-only
    :disabled="createDisabled"
    :title="createDisabled ? createDisabledReason : `新建${title}`"
    @click="emit('create')"
  />
</template>
