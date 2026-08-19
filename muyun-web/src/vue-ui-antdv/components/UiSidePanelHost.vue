<script setup lang="ts">
import { provide, ref } from 'vue';
import { sidePanelHostKey } from './sidePanelHost';

defineOptions({ name: 'UiSidePanelHost', inheritAttrs: false });

const host = ref<HTMLElement>();
provide(sidePanelHostKey, host);
</script>

<template>
  <section ref="host" class="ui-side-panel-host" :class="$attrs.class" :style="$attrs.style">
    <slot />
  </section>
</template>

<style scoped>
.ui-side-panel-host {
  position: relative;
  isolation: isolate;
  min-width: 0;
  min-height: 0;
  height: 100%;
}
</style>
