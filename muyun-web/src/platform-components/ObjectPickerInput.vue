<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, ref, watch } from 'vue';
import { UiButton, UiSelect, UiSearchInput } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

/**
 * Standard compact entry for a value chosen from a larger candidate surface.
 * It keeps the selected display value in the control and delegates actual candidate browsing
 * to its owner; it does not infer candidate source, authorization, or persisted identity.
 */
defineOptions({ name: 'ObjectPickerInput' });

const props = withDefaults(
  defineProps<{
    value?: string;
    /** Authorized display items; only removals can be made from this compact multi-value entry. */
    selectionItems?: readonly { key: string; label: string }[];
    allowClear?: boolean;
    linked?: boolean;
    unmatched?: boolean;
    /** The owner has an unresolved draft, so asynchronous display refreshes must not overwrite it. */
    preserveDraft?: boolean;
    /** Changes on confirmed selection, including reselecting the same value. */
    selectionVersion?: number;
    placeholder?: string;
    invalid?: boolean;
    disabled?: boolean;
    browseLabel?: string;
  }>(),
  {
    value: '',
    selectionItems: undefined,
    allowClear: true,
    linked: false,
    unmatched: false,
    preserveDraft: false,
    selectionVersion: 0,
    placeholder: undefined,
    invalid: false,
    disabled: false,
    browseLabel: undefined,
  },
);

const resolvedPlaceholder = computed(
  () => props.placeholder ?? language.t('platform.form.search-select', '搜索并选择'),
);
const resolvedBrowseLabel = computed(
  () => props.browseLabel ?? language.t('platform.screen.ObjectPickerInput.browseLabel', '打开候选选择'),
);

const emit = defineEmits<{
  browse: [keyword: string];
  clear: [];
  /** A changed draft lets the owner invalidate any in-flight completion request. */
  'draft-change': [value: string];
  /** A non-empty user draft has left the compact entry. */
  blur: [value: string];
  'update:selectionKeys': [keys: string[]];
}>();

const inputValue = ref(props.selectionItems ? '' : props.value);
const draftResetVersion = ref(0);
const selectionOptions = computed(
  () => props.selectionItems?.map((item) => ({ value: item.key, label: item.label })) ?? [],
);
const draftEdited = ref(false);
let clearEmitted = false;

watch(
  () => [props.value, props.selectionVersion] as const,
  ([value, selectionVersion], [, previousSelectionVersion]) => {
    if (props.preserveDraft && selectionVersion === previousSelectionVersion) return;
    if (draftEdited.value && selectionVersion === previousSelectionVersion) return;
    // A closed multi-select keeps a private input draft; recreate it when discarding that draft.
    if (props.selectionItems && inputValue.value) draftResetVersion.value += 1;
    inputValue.value = props.selectionItems ? '' : value;
    draftEdited.value = false;
    clearEmitted = false;
  },
);

function updateDraft(value: string) {
  inputValue.value = value;
  draftEdited.value = true;
  if (value.trim()) clearEmitted = false;
  emit('draft-change', value);
  if (!props.selectionItems && !value.trim() && props.value && !clearEmitted) {
    clearEmitted = true;
    emit('clear');
  }
}

function browse(value: string, source?: 'input' | 'clear') {
  draftEdited.value = false;
  if (source === 'clear') {
    if (!clearEmitted) {
      clearEmitted = true;
      emit('clear');
    }
    return;
  }
  emit('browse', value === props.value ? '' : value);
}

function browseOnDoubleClick() {
  if (props.disabled) return;
  browse(inputValue.value);
}

function completeDraft(event: FocusEvent) {
  const nextFocus = event.relatedTarget;
  const ownControl =
    event.target instanceof Element ? event.target.closest('.object-picker-input-control') : null;
  if (
    nextFocus instanceof Element &&
    ownControl != null &&
    nextFocus.closest('.object-picker-input-control') === ownControl
  )
    return;
  if (
    (!draftEdited.value && !props.preserveDraft) ||
    !inputValue.value.trim() ||
    inputValue.value === props.value
  )
    return;
  draftEdited.value = false;
  emit('blur', inputValue.value);
}
</script>

<template>
  <UiSelect
    v-if="selectionItems"
    :key="draftResetVersion"
    class="object-picker-input-control"
    :invalid="invalid"
    :unmatched="unmatched"
    :value="selectionItems.map((item) => item.key)"
    :options="selectionOptions"
    mode="multiple"
    max-tag-count="responsive"
    :max-tag-text-length="24"
    :open="false"
    :show-search="true"
    :search-value="inputValue"
    :filter-option="false"
    :placeholder="resolvedPlaceholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :aria-label="resolvedBrowseLabel"
    @search="updateDraft"
    @blur="completeDraft"
    @keydown.enter.prevent="browse(inputValue)"
    @dblclick="browseOnDoubleClick"
    @update:value="
      emit(
        'update:selectionKeys',
        Array.isArray($event) ? $event.filter((key): key is string => typeof key === 'string') : [],
      )
    "
  >
    <template #suffixAction>
      <UiButton
        type="text"
        size="small"
        icon-name="search"
        icon-only
        tabindex="-1"
        :aria-label="resolvedBrowseLabel"
        :title="resolvedBrowseLabel"
        :disabled="disabled"
        @click="browse(inputValue)"
      />
    </template>
  </UiSelect>
  <UiSearchInput
    v-else
    :invalid="invalid"
    class="object-picker-input-control"
    :value="inputValue"
    :placeholder="resolvedPlaceholder"
    :disabled="disabled"
    :linked="linked && !!value && inputValue === value"
    :unmatched="unmatched"
    search-icon-only
    :aria-label="resolvedBrowseLabel"
    @update:value="updateDraft"
    @search="browse"
    @dblclick="browseOnDoubleClick"
    @blur="completeDraft"
  />
</template>

<style scoped>
.object-picker-input-control {
  width: 100%;
}
</style>
