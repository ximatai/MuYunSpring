<script setup lang="ts">
import { useLanguageContext, type ModuleContext } from '@muyun/web-core';
import { ModuleActionButton, RecordPanelButton } from '@muyun/platform-components';

import type { NavigatorSortViewState } from './composables/useNavigatorRuntime';

const language = useLanguageContext();

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
    :title="
      sort.disabledReason ??
      (sort.active
        ? language.t('platform.screen.NavigatorPanelActions.finish-reordering', '结束排序')
        : language.t('platform.screen.NavigatorPanelActions.reorder', '调整排序'))
    "
    :aria-label="
      sort.active
        ? language.t('platform.screen.NavigatorPanelActions.finish-reordering', '结束排序')
        : language.t('platform.screen.NavigatorPanelActions.reorder', '调整排序')
    "
    @click="emit('toggle-sorting')"
  />
  <ModuleActionButton
    v-if="createAvailable"
    :context="context"
    action-code="create"
    icon-only
    :disabled="createDisabled"
    :title="
      createDisabled
        ? createDisabledReason
        : language.t('platform.screen.NavigatorPanelActions.create-value', '新建{value1}', { value1: title })
    "
    @click="emit('create')"
  />
</template>
