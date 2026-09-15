<script setup lang="ts">
import { computed } from 'vue';
import type { DictionaryOptionTreeNode, DictionarySelectionMode } from './dictionaryOptionDialogModel';

defineOptions({ name: 'DictionaryOptionDialogChoiceList' });

const props = withDefaults(
  defineProps<{
    nodes: readonly DictionaryOptionTreeNode[];
    selectedCodes: readonly string[];
    selectionMode?: DictionarySelectionMode;
    disabled?: boolean;
    nested?: boolean;
  }>(),
  {
    selectionMode: 'SINGLE',
    disabled: false,
    nested: false,
  },
);

const emit = defineEmits<{
  'update:selectedCodes': [codes: string[]];
  /** A single-value dialog may use a double click as an explicit choose-and-confirm shortcut. */
  'double-click': [code: string];
}>();

const selected = computed(() => new Set(props.selectedCodes));

function select(node: DictionaryOptionTreeNode) {
  if (props.disabled || node.disabled) return;
  if (props.selectionMode === 'SINGLE') {
    emit('update:selectedCodes', [node.value]);
    return;
  }
  const next = new Set(selected.value);
  if (next.has(node.value)) next.delete(node.value);
  else next.add(node.value);
  emit('update:selectedCodes', [...next]);
}

function confirmOnDoubleClick(node: DictionaryOptionTreeNode) {
  if (props.selectionMode !== 'SINGLE' || props.disabled || node.disabled) return;
  emit('double-click', node.value);
}
</script>

<template>
  <ul class="dictionary-option-choice-list" :class="{ 'is-nested': nested }">
    <li v-for="node in nodes" :key="node.value" class="dictionary-option-choice-list__item">
      <button
        type="button"
        class="dictionary-option-choice-list__choice"
        :class="{ 'is-selected': selected.has(node.value) }"
        :disabled="disabled || node.disabled"
        :role="selectionMode === 'MULTIPLE' ? 'checkbox' : 'radio'"
        :aria-checked="selected.has(node.value)"
        @click="select(node)"
        @dblclick="confirmOnDoubleClick(node)"
      >
        <span class="dictionary-option-choice-list__indicator" aria-hidden="true">
          {{ selected.has(node.value) ? '✓' : '' }}
        </span>
        <span>{{ node.title }}</span>
        <small>{{ node.value }}</small>
      </button>
      <DictionaryOptionDialogChoiceList
        v-if="node.children?.length"
        :nodes="node.children"
        :selected-codes="selectedCodes"
        :selection-mode="selectionMode"
        :disabled="disabled"
        nested
        @update:selected-codes="emit('update:selectedCodes', $event)"
        @double-click="emit('double-click', $event)"
      />
    </li>
  </ul>
</template>

<style scoped>
.dictionary-option-choice-list {
  display: grid;
  gap: 6px;
  max-height: 260px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.dictionary-option-choice-list.is-nested {
  max-height: none;
  margin: 6px 0 0 20px;
}

.dictionary-option-choice-list__choice {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  width: 100%;
  gap: 8px;
  padding: 9px 10px;
  color: var(--muyun-text);
  text-align: left;
  background: var(--muyun-surface);
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  cursor: pointer;
}

.dictionary-option-choice-list__choice:hover:not(:disabled),
.dictionary-option-choice-list__choice.is-selected {
  border-color: var(--muyun-primary);
  background: var(--muyun-primary-surface, var(--muyun-hover));
}

.dictionary-option-choice-list__choice:disabled {
  color: var(--muyun-text-disabled, var(--muyun-text-secondary));
  cursor: not-allowed;
}

.dictionary-option-choice-list__indicator {
  display: inline-grid;
  width: 16px;
  height: 16px;
  place-items: center;
  color: white;
  font-size: 11px;
  background: var(--muyun-surface);
  border: 1px solid var(--muyun-border);
  border-radius: 50%;
}

.is-selected .dictionary-option-choice-list__indicator {
  background: var(--muyun-primary);
  border-color: var(--muyun-primary);
}

.dictionary-option-choice-list__choice small {
  color: var(--muyun-text-secondary);
}
</style>
