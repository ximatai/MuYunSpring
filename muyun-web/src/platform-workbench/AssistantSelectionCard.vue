<script setup lang="ts">
import { computed } from 'vue';
import { UiButton, UiIcon } from '@muyun/vue-ui-antdv';
import type { AssistantSelectionInteraction, AssistantSelectionOption } from '@muyun/web-contracts';

defineOptions({ name: 'AssistantSelectionCard' });

const props = defineProps<{
  selection: AssistantSelectionInteraction;
  state: 'open' | 'submitting' | 'answered' | 'superseded';
  selectedOptionId?: string;
}>();

const emit = defineEmits<{ select: [option: AssistantSelectionOption]; abandon: [] }>();

const selectedLabel = computed(
  () => props.selection.options.find(({ id }) => id === props.selectedOptionId)?.label,
);
</script>

<template>
  <div
    class="assistant-selection"
    :class="{
      'assistant-selection--required': selection.inputPolicy === 'selection_required',
      'assistant-selection--confirmation': selection.presentation === 'confirmation',
    }"
  >
    <div v-if="state === 'answered'" class="assistant-selection__resolved">
      <UiIcon name="check" />
      <span>{{ selectedLabel ? `已选择：${selectedLabel}` : '已完成选择' }}</span>
    </div>
    <div v-else-if="state === 'superseded'" class="assistant-selection__resolved">
      <UiIcon name="minus" />
      <span>此选择已更新</span>
    </div>
    <template v-else>
      <strong>{{ selection.prompt }}</strong>
      <div class="assistant-selection__options">
        <UiButton
          v-for="option in selection.options"
          :key="option.id"
          :type="selection.presentation === 'confirmation' && option.id === 'confirm' ? 'primary' : 'default'"
          :disabled="state !== 'open'"
          :loading="state === 'submitting' && option.id === selectedOptionId"
          @click="emit('select', option)"
        >
          {{ option.label }}
        </UiButton>
      </div>
      <UiButton
        v-if="selection.inputPolicy === 'selection_required'"
        type="text"
        :disabled="state !== 'open'"
        @click="emit('abandon')"
      >
        放弃本次提议
      </UiButton>
      <span v-if="selection.inputPolicy === 'free_text_allowed'" class="assistant-selection__hint">
        也可以继续输入其他内容
      </span>
    </template>
  </div>
</template>

<style scoped>
.assistant-selection {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
}

.assistant-selection--required {
  border-color: var(--muyun-warning-border);
  background: var(--muyun-warning-soft);
}

.assistant-selection__options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.assistant-selection--confirmation .assistant-selection__options > * {
  flex: 1;
}

.assistant-selection__hint,
.assistant-selection__resolved {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}

.assistant-selection__resolved {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
