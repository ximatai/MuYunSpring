<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton, UiModal, UiSearchInput } from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import DictionaryOptionDialogChoiceList from './DictionaryOptionDialogChoiceList.vue';
import {
  dictionaryOptionCodes,
  dictionaryOptionItemsToTree,
  dictionaryOptionValue,
  selectableDictionaryCodes,
  type DictionaryOptionValue,
  type DictionarySelectionMode,
} from './dictionaryOptionDialogModel';

defineOptions({ name: 'DictionaryOptionDialog' });

const props = withDefaults(
  defineProps<{
    /** Controlled visibility lets a field renderer decide when a dictionary uses dialog presentation. */
    open: boolean;
    /** Dictionary values are codes, never dictionary-item record IDs or display titles. */
    value?: string | readonly string[];
    items: readonly OptionItemDescriptor[];
    selectionMode?: DictionarySelectionMode;
    title?: string;
    /** A picker may hand its unfinished dropdown search to the dialog rather than losing it. */
    initialKeyword?: string;
    searchPlaceholder?: string;
    disabled?: boolean;
  }>(),
  {
    value: undefined,
    selectionMode: 'SINGLE',
    title: '选择字典项',
    initialKeyword: '',
    searchPlaceholder: '按名称或编码搜索',
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:open': [open: boolean];
  /** Emitted only after explicit confirmation; MULTIPLE always emits an array, including an empty one. */
  'update:value': [value: DictionaryOptionValue];
  confirm: [value: DictionaryOptionValue];
  cancel: [];
  /** The action only clears the dialog draft; confirmation owns the external mutation. */
  clear: [];
}>();

const keyword = ref('');
const draftCodes = ref<string[]>([]);
const externalCodes = computed(() => dictionaryOptionCodes(props.value, props.selectionMode));
const treeData = computed(() => dictionaryOptionItemsToTree(props.items, keyword.value));
const selectedText = computed(() => {
  if (!draftCodes.value.length) return '尚未选择';
  const titles = new Map(props.items.map((item) => [item.code, item.title]));
  return draftCodes.value.map((code) => titles.get(code) ?? code).join('、');
});

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    // A reopen starts from the host value, never from a cancelled dialog draft.
    draftCodes.value = [...externalCodes.value];
    keyword.value = props.initialKeyword;
  },
  { immediate: true },
);

function updateDraft(value: unknown) {
  if (props.disabled) return;
  draftCodes.value = selectableDictionaryCodes(value, externalCodes.value, props.items, props.selectionMode);
}

function clearDraft() {
  if (props.disabled || !draftCodes.value.length) return;
  draftCodes.value = [];
  emit('clear');
}

function confirm() {
  if (props.disabled) return;
  commit(draftCodes.value);
}

function confirmSingleOption(code: string) {
  if (props.disabled || props.selectionMode !== 'SINGLE') return;
  const codes = selectableDictionaryCodes([code], externalCodes.value, props.items, props.selectionMode);
  if (!codes.length) return;
  draftCodes.value = codes;
  commit(codes);
}

function commit(codes: readonly string[]) {
  const value = dictionaryOptionValue(codes, props.selectionMode);
  emit('update:value', value);
  emit('confirm', value);
  close();
}

function cancel() {
  emit('cancel');
  close();
}

function close() {
  emit('update:open', false);
}
</script>

<template>
  <UiModal
    :open="open"
    :title="title"
    :width="560"
    :confirm-disabled="disabled"
    :closable="!disabled"
    @confirm="confirm"
    @cancel="cancel"
  >
    <div class="dictionary-option-dialog">
      <UiSearchInput
        :value="keyword"
        :placeholder="searchPlaceholder"
        :disabled="disabled"
        search-text="搜索"
        @update:value="keyword = $event"
        @search="keyword = $event"
      />
      <DictionaryOptionDialogChoiceList
        :nodes="treeData"
        :selected-codes="draftCodes"
        :selection-mode="selectionMode"
        :disabled="disabled"
        data-testid="dictionary-option-choice-list"
        @update:selected-codes="updateDraft"
        @double-click="confirmSingleOption"
      />
      <div class="dictionary-option-dialog-selection">
        <span>当前选择</span>
        <strong>{{ selectedText }}</strong>
        <UiButton type="link" size="small" :disabled="disabled || !draftCodes.length" @click="clearDraft">
          清除
        </UiButton>
      </div>
    </div>
  </UiModal>
</template>

<style scoped>
.dictionary-option-dialog {
  display: grid;
  gap: 12px;
  min-height: 220px;
}

.dictionary-option-dialog-selection {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muyun-text-secondary);
  font-size: 12px;
}

.dictionary-option-dialog-selection strong {
  color: var(--muyun-text);
  font-weight: 500;
}
</style>
