<script setup lang="ts">
import { ref } from 'vue';
import UiButton from './UiButton.vue';
import UiPopover from './UiPopover.vue';

defineOptions({ name: 'UiHelp', inheritAttrs: false });
// Keep this control beside the field label, outside its input's <label> element.
defineProps<{ label: string }>();
const open = ref(false);
const trigger = ref<InstanceType<typeof UiButton>>();
function close() {
  open.value = false;
  trigger.value?.$el?.focus();
}
</script>

<template>
  <span class="ui-help" :class="$attrs.class" :style="$attrs.style">
    <UiPopover v-model:open="open" placement="bottomLeft">
      <UiButton
        ref="trigger"
        type="text"
        size="small"
        icon-name="help"
        icon-only
        :aria-label="label"
        :aria-expanded="open"
        @keydown.esc.stop.prevent="close"
      />
      <template #content>
        <div class="ui-help-content" @keydown.esc.stop.prevent="close"><slot /></div>
      </template>
    </UiPopover>
  </span>
</template>

<style scoped>
.ui-help {
  display: inline-flex;
  align-items: center;
}
.ui-help-content {
  max-width: min(320px, calc(100vw - 48px));
  overflow-wrap: anywhere;
  font-size: var(--muyun-font-size-body, 13px);
  line-height: 1.6;
}
</style>
