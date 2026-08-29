<script setup lang="ts">
import RecordPanelButton from './RecordPanelButton.vue';

defineOptions({ name: 'RecordExplorerCreateButton' });

const props = withDefaults(
  defineProps<{
    /** Accessible action name, also shown as the native button tooltip. */
    title: string;
    disabled?: boolean;
    loading?: boolean;
  }>(),
  {
    disabled: false,
    loading: false,
  },
);

const emit = defineEmits<{ click: [event: MouseEvent] }>();
</script>

<template>
  <RecordPanelButton
    class="record-explorer-panel-create-action"
    type="default"
    icon-name="plus"
    icon-only
    :title="props.title"
    :aria-label="props.title"
    :disabled="disabled"
    :loading="loading"
    @click="emit('click', $event)"
  />
</template>
