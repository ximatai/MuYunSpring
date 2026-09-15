<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton, UiSelect, UiTreeSelect } from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import ObjectPickerInput from './ObjectPickerInput.vue';
import DictionaryOptionDialog from './DictionaryOptionDialog.vue';
import {
  dictionaryOptionCodes,
  dictionaryOptionItemsToOptions,
  dictionaryOptionItemsToTree,
  dictionaryOptionValue,
  selectableDictionaryCodes,
  type DictionaryOptionValue,
  type DictionarySelectionMode,
} from './dictionaryOptionDialogModel';

defineOptions({ name: 'DictionaryPicker' });

export type DictionaryPickerMode = 'dropdown' | 'dialog';

/** Whether a compact dictionary draft still represents a selectable dictionary code. */
export interface DictionaryPickerValidity {
  valid: boolean;
  status: 'ready' | 'editing' | 'unmatched' | 'ambiguous' | 'disabled';
  message?: string;
}

const props = withDefaults(
  defineProps<{
    /** The business value is always a dictionary item code, never the dictionary-item record ID. */
    value?: string | readonly string[];
    items: readonly OptionItemDescriptor[];
    selectionMode?: DictionarySelectionMode;
    mode?: DictionaryPickerMode;
    title?: string;
    placeholder?: string;
    searchPlaceholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
  }>(),
  {
    value: undefined,
    selectionMode: 'SINGLE',
    mode: 'dialog',
    title: '选择字典项',
    placeholder: '搜索并选择',
    searchPlaceholder: '按名称或编码搜索',
    disabled: false,
    allowClear: true,
  },
);

const emit = defineEmits<{
  /** Dropdown selection is immediate; dialog selection emits only after confirmation. */
  'update:value': [value: DictionaryOptionValue];
  select: [value: DictionaryOptionValue];
  confirm: [value: DictionaryOptionValue];
  cancel: [];
  /** Clear in the dialog changes only its draft until confirmation. */
  clear: [];
  'validity-change': [validity: DictionaryPickerValidity];
}>();

const open = ref(false);
const dialogKeyword = ref('');
const compactInputVersion = ref(0);
const dropdownDraft = ref('');
const dropdownTextEdited = ref(false);
const dropdownLastInputValue = ref<string>();
const dropdownClearRequested = ref(false);
const validity = ref<DictionaryPickerValidity>({ valid: true, status: 'ready' });

const externalCodes = computed(() => dictionaryOptionCodes(props.value, props.selectionMode));
const hasHierarchy = computed(() => props.items.some((item) => Boolean(item.parentCode)));
const dropdownTreeData = computed(() => dictionaryOptionItemsToTree(props.items, dropdownDraft.value));
const dropdownOptions = computed(() => dictionaryOptionItemsToOptions(props.items, dropdownDraft.value));
const invalidDropdownDraft = computed(() =>
  ['unmatched', 'ambiguous', 'disabled'].includes(validity.value.status) ? dropdownDraft.value : '',
);
const pickerValue = computed(() =>
  props.selectionMode === 'MULTIPLE' ? externalCodes.value : externalCodes.value[0],
);
// Keep the persisted code intact until blur, but do not let Ant Select repaint its selected
// title over an explicitly emptied text draft in that interval.
const dropdownPickerValue = computed(() => (dropdownClearRequested.value ? undefined : pickerValue.value));
const selectionSummary = computed(() => {
  if (!externalCodes.value.length) return '';
  const titles = new Map(props.items.map((item) => [item.code, item.title]));
  return externalCodes.value.map((code) => titles.get(code) ?? code).join('、');
});
// Ant Select switches its selected label to the search input while it has focus. Give that
// input the selected title until the user actually edits it, so prior input remains visible
// and can be selected/copied without turning a focus event into an empty search draft.
const dropdownSearchValue = computed(() =>
  dropdownClearRequested.value
    ? ''
    : dropdownDraft.value || (props.selectionMode === 'SINGLE' ? selectionSummary.value : ''),
);

watch(validity, (next) => emit('validity-change', next), { immediate: true, flush: 'sync' });
watch(externalCodes, (next, previous) => {
  if (!sameCodes(next, previous ?? [])) {
    dropdownDraft.value = '';
    dropdownTextEdited.value = false;
    dropdownLastInputValue.value = undefined;
    dropdownClearRequested.value = false;
    setValidity('ready');
  }
});
function openDialog(initialKeyword = '') {
  if (props.disabled) return;
  dialogKeyword.value = initialKeyword;
  compactInputVersion.value += 1;
  open.value = true;
}

function closeDialog() {
  open.value = false;
  dialogKeyword.value = '';
  compactInputVersion.value += 1;
}

function updateDropdown(value: unknown) {
  if (props.disabled) return;
  const codes = selectableDictionaryCodes(value, externalCodes.value, props.items, props.selectionMode);
  const next = dictionaryOptionValue(codes, props.selectionMode);
  dropdownDraft.value = '';
  dropdownTextEdited.value = false;
  dropdownLastInputValue.value = undefined;
  dropdownClearRequested.value = false;
  setValidity('ready');
  emit('update:value', next);
  emit('select', next);
}

/**
 * A dropdown is still a chooser, not a free-text field. Keep the typed text as a draft until
 * its blur can resolve it against dictionary title/code; this is the same interaction boundary
 * as record-reference completion, but entirely local to the dictionary candidate set.
 */
function updateDropdownDraft(value: string) {
  dropdownClearRequested.value = false;
  dropdownDraft.value = value;
  if (!value.trim()) {
    setValidity('ready');
    return;
  }
  setValidity('editing', '请完成字典选择');
}

// Ant Design Select clears its transient search text as it blurs. That is presentation cleanup,
// not user intent to discard a pasted draft; keep the last non-empty draft until completion.
function updateDropdownSearch(value: string) {
  if (!value.trim()) {
    if (dropdownTextEdited.value && dropdownLastInputValue.value === '') {
      dropdownDraft.value = '';
      dropdownClearRequested.value = props.selectionMode === 'SINGLE' && externalCodes.value.length > 0;
      setValidity('ready');
      return;
    }
    // Ant Design reports an empty transient search value when a single select gains focus.
    // Keep both an existing pasted draft and the selected title intact; clear remains an
    // explicit value action from the Select clear affordance.
    if (dropdownDraft.value.trim() || externalCodes.value.length) return;
  }
  updateDropdownDraft(value);
}

function markDropdownTextEdited(event: Event) {
  if (props.mode !== 'dropdown' || props.disabled || !(event.target instanceof HTMLInputElement)) return;
  dropdownTextEdited.value = true;
  dropdownLastInputValue.value = event.target.value;
}

function completeDropdownDraft() {
  const draft = dropdownDraft.value.trim();
  if (props.disabled || open.value || props.mode !== 'dropdown') return;
  if (dropdownClearRequested.value) {
    updateDropdown(undefined);
    return;
  }
  if (!draft) return;
  if (props.selectionMode === 'MULTIPLE') {
    openDialog(draft);
    return;
  }
  const matches = dictionaryDraftMatches(draft, props.items);
  if (matches.length === 1) {
    const item = matches[0]!;
    if (item.enabled) {
      updateDropdown(item.code);
      return;
    }
    setValidity('disabled', '匹配到的字典项已停用，不能选择');
    return;
  }
  if (matches.length > 1) {
    setValidity('ambiguous', '匹配到多个字典项，请通过下拉或搜索选择');
    return;
  }
  setValidity('unmatched', '未找到匹配的字典项');
}

function completeDropdownFocusout(event: FocusEvent) {
  if (props.mode !== 'dropdown') return;
  const picker = event.currentTarget;
  const nextFocus = event.relatedTarget;
  if (picker instanceof Element && nextFocus instanceof Node && picker.contains(nextFocus)) return;
  completeDropdownDraft();
}

function updateDialogValue(value: DictionaryOptionValue) {
  emit('update:value', value);
  emit('select', value);
}

function confirmDialogValue(value: DictionaryOptionValue) {
  emit('confirm', value);
}

function closeDialogFromChild(nextOpen: boolean) {
  if (!nextOpen) closeDialog();
}

function setValidity(status: DictionaryPickerValidity['status'], message?: string) {
  const next: DictionaryPickerValidity =
    status === 'ready' ? { valid: true, status } : { valid: false, status, ...(message ? { message } : {}) };
  if (
    validity.value.valid === next.valid &&
    validity.value.status === next.status &&
    validity.value.message === next.message
  )
    return;
  validity.value = next;
}

function sameCodes(left: readonly string[], right: readonly string[]) {
  return left.length === right.length && left.every((code, index) => code === right[index]);
}

function dictionaryDraftMatches(draft: string, items: readonly OptionItemDescriptor[]) {
  const normalized = draft.toLocaleLowerCase();
  const codeMatches = items.filter((item) => item.code.toLocaleLowerCase() === normalized);
  if (codeMatches.length) return codeMatches;
  return items.filter((item) => item.title.trim().toLocaleLowerCase() === normalized);
}
</script>

<template>
  <div
    class="dictionary-picker"
    @focusout.capture="completeDropdownFocusout"
    @input.capture="markDropdownTextEdited"
  >
    <template v-if="mode === 'dropdown'">
      <UiTreeSelect
        v-if="hasHierarchy"
        :value="dropdownPickerValue"
        :tree-data="dropdownTreeData"
        :mode="selectionMode === 'MULTIPLE' ? 'multiple' : undefined"
        :placeholder="placeholder"
        :disabled="disabled"
        :allow-clear="allowClear"
        :show-search="true"
        :filter-tree-node="false"
        :search-value="dropdownSearchValue"
        :unmatched="
          validity.status === 'unmatched' || validity.status === 'ambiguous' || validity.status === 'disabled'
        "
        @search="updateDropdownSearch"
        @update:search-value="updateDropdownSearch"
        @update:value="updateDropdown"
        @dblclick="openDialog(dropdownDraft)"
      >
        <template #suffixAction>
          <UiButton
            class="dictionary-picker__browse-action"
            type="default"
            icon-name="search"
            icon-only
            :aria-label="title"
            :title="title"
            :disabled="disabled"
            @click="openDialog(dropdownDraft)"
          />
        </template>
      </UiTreeSelect>
      <UiSelect
        v-else
        :value="dropdownPickerValue"
        :options="dropdownOptions"
        :mode="selectionMode === 'MULTIPLE' ? 'multiple' : undefined"
        :placeholder="placeholder"
        :disabled="disabled"
        :allow-clear="allowClear"
        :show-search="true"
        :filter-option="false"
        :search-value="dropdownSearchValue"
        :unmatched="
          validity.status === 'unmatched' || validity.status === 'ambiguous' || validity.status === 'disabled'
        "
        @search="updateDropdownSearch"
        @update:search-value="updateDropdownSearch"
        @update:value="updateDropdown"
        @dblclick="openDialog(dropdownDraft)"
      >
        <template #suffixAction>
          <UiButton
            class="dictionary-picker__browse-action"
            type="default"
            icon-name="search"
            icon-only
            :aria-label="title"
            :title="title"
            :disabled="disabled"
            @click="openDialog(dropdownDraft)"
          />
        </template>
      </UiSelect>
    </template>
    <ObjectPickerInput
      v-else
      :value="selectionSummary"
      :selection-version="compactInputVersion"
      :linked="externalCodes.length > 0"
      :placeholder="placeholder"
      :disabled="disabled"
      :browse-label="title"
      @browse="openDialog"
      @clear="openDialog('')"
      @blur="openDialog"
    />
    <span
      v-if="mode === 'dropdown' && invalidDropdownDraft"
      class="dictionary-picker__unmatched-draft"
      aria-hidden="true"
    >
      {{ invalidDropdownDraft }}
    </span>

    <DictionaryOptionDialog
      :open="open"
      :value="pickerValue"
      :items="items"
      :selection-mode="selectionMode"
      :title="title"
      :initial-keyword="dialogKeyword"
      :search-placeholder="searchPlaceholder"
      :disabled="disabled"
      @update:open="closeDialogFromChild"
      @update:value="updateDialogValue"
      @confirm="confirmDialogValue"
      @cancel="emit('cancel')"
      @clear="emit('clear')"
    />
  </div>
</template>

<style scoped>
.dictionary-picker {
  position: relative;
}

.dictionary-picker__unmatched-draft {
  position: absolute;
  z-index: 1;
  top: 50%;
  right: 52px;
  left: 12px;
  overflow: hidden;
  color: var(--muyun-warning-soft-text);
  line-height: 20px;
  text-decoration: line-through;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
  transform: translateY(-50%);
}

/* Matches ObjectPickerInput: the browse action is a distinct, primary-colored input segment. */
.dictionary-picker :deep(.ui-select-suffix-action),
.dictionary-picker :deep(.ui-tree-select-suffix-action) {
  top: 0;
  right: 0;
  height: 100%;
  transform: none;
}

.dictionary-picker :deep(.dictionary-picker__browse-action) {
  width: 44px;
  min-width: 44px;
  height: 100%;
  border-radius: 0 var(--muyun-radius-sm, 4px) var(--muyun-radius-sm, 4px) 0;
}

.dictionary-picker :deep(.dictionary-picker__browse-action:not(:disabled)) {
  color: var(--muyun-theme-base);
  background: var(--muyun-theme-soft);
  border-color: var(--muyun-theme-border);
}

.dictionary-picker :deep(.dictionary-picker__browse-action:not(:disabled):hover),
.dictionary-picker :deep(.dictionary-picker__browse-action:not(:disabled):active) {
  background: var(--muyun-theme-focus);
  border-color: var(--muyun-theme-base);
}

/* Keep clear away from the 44px browse segment, matching ObjectPickerInput's input padding. */
.dictionary-picker :deep(.ui-select--suffix-action .ant-select-clear),
.dictionary-picker :deep(.ui-tree-select--suffix-action .ant-select-clear) {
  right: 58px;
}
</style>
