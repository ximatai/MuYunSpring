<script setup lang="ts">
import { computed, ref } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiActionButton, UiSidePanel, resolveUiDrawerWidth, type UiDrawerWidth } from '@muyun/vue-ui-antdv';
import RecordDetailLayout from './RecordDetailLayout.vue';
import { provideRecordSurfaceActivity } from './recordSurfaceActivity';

/** Moves one mounted detail tree between the workspace dock and the standard side panel. */
defineOptions({ name: 'RecordAdaptiveDetailPanel', inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    open: boolean;
    docked: boolean;
    title: string;
    width?: UiDrawerWidth;
  }>(),
  { width: 'standard' },
);
const emit = defineEmits<{ close: [] }>();
const language = useLanguageContext();
const dockHost = ref<HTMLElement>();
const drawerHost = ref<HTMLElement>();
const target = computed(() => (props.docked ? dockHost.value : drawerHost.value));
provideRecordSurfaceActivity(() => props.open);
</script>

<template>
  <aside
    ref="dockHost"
    v-show="open && docked"
    class="record-adaptive-detail__dock"
    :style="{ width: `${resolveUiDrawerWidth(width)}px` }"
    :aria-label="title"
  />
  <UiSidePanel :open="open && !docked" :width="width" force-render @close="emit('close')">
    <div ref="drawerHost" class="record-adaptive-detail__drawer" />
  </UiSidePanel>
  <Teleport v-if="target" :to="target">
    <RecordDetailLayout :title="title" surface="drawer" scrollable-content>
      <template #actions>
        <UiActionButton
          emphasis="quiet"
          icon-name="close"
          :title="language.t('platform.screen.RecordDetailDrawer.close', '关闭')"
          @click="emit('close')"
        />
      </template>
      <slot />
      <template v-if="$slots.operation" #operation><slot name="operation" /></template>
    </RecordDetailLayout>
  </Teleport>
</template>

<style scoped>
.record-adaptive-detail__dock {
  flex: 0 0 auto;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.record-adaptive-detail__drawer {
  height: 100%;
}
</style>
