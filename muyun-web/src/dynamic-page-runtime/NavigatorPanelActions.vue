<script setup lang="ts">
import { ModuleActionButton, RecordPanelButton } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';

defineOptions({ name: 'NavigatorPanelActions' });

withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    title: string;
    keyword: string;
    sort: {
      available: boolean;
    };
    sorting: boolean;
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
    v-if="sort.available"
    icon-name="swap-vertical"
    icon-only
    size="small"
    type="text"
    :selected="sorting"
    :disabled="Boolean(keyword.trim())"
    :title="keyword.trim() ? '清空搜索后可调整排序' : sorting ? '结束排序' : '调整排序'"
    :aria-label="sorting ? '结束排序' : '调整排序'"
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
