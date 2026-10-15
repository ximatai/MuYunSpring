<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiEmpty, UiSpin } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'RecordPanelState' });

const props = withDefaults(
  defineProps<{
    loading?: boolean;
    description: string;
    loadingTip?: string;
  }>(),
  {
    loading: false,
    loadingTip: undefined,
  },
);

const resolvedLoadingTip = computed(
  () => props.loadingTip ?? language.t('platform.screen.RecordPanelState.loadingTip', '加载中'),
);
</script>

<template>
  <UiSpin v-if="loading" :tip="resolvedLoadingTip" />
  <UiEmpty v-else :description="description" />
</template>
