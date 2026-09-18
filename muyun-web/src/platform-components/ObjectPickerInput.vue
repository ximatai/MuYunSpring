<script setup lang="ts">
import { ref, watch } from 'vue';
import { UiSearchInput } from '@muyun/vue-ui-antdv';

/**
 * Standard compact entry for a value chosen from a larger candidate surface.
 * It keeps the selected display value in the control and delegates actual candidate browsing
 * to its owner; it does not infer candidate source, authorization, or persisted identity.
 */
defineOptions({ name: 'ObjectPickerInput' });

const props = withDefaults(
  defineProps<{
    value?: string;
    linked?: boolean;
    unmatched?: boolean;
    /** The owner has an unresolved draft, so asynchronous display refreshes must not overwrite it. */
    preserveDraft?: boolean;
    /** Changes on confirmed selection, including reselecting the same value. */
    selectionVersion?: number;
    placeholder?: string;
    disabled?: boolean;
    browseLabel?: string;
  }>(),
  {
    value: '',
    linked: false,
    unmatched: false,
    preserveDraft: false,
    selectionVersion: 0,
    placeholder: '搜索并选择',
    disabled: false,
    browseLabel: '打开候选选择',
  },
);

const emit = defineEmits<{
  browse: [keyword: string];
  clear: [];
  /** A changed draft lets the owner invalidate any in-flight completion request. */
  'draft-change': [value: string];
  /** A non-empty user draft has left the compact entry. */
  blur: [value: string];
}>();

const inputValue = ref(props.value);
const draftEdited = ref(false);
let clearEmitted = false;

watch(
  () => [props.value, props.selectionVersion] as const,
  ([value, selectionVersion], [, previousSelectionVersion]) => {
    if (props.preserveDraft && selectionVersion === previousSelectionVersion) return;
    if (draftEdited.value && selectionVersion === previousSelectionVersion) return;
    inputValue.value = value;
    draftEdited.value = false;
    clearEmitted = false;
  },
);

function updateDraft(value: string) {
  inputValue.value = value;
  draftEdited.value = true;
  if (value.trim()) clearEmitted = false;
  emit('draft-change', value);
  if (!value.trim() && props.value && !clearEmitted) {
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
  <UiSearchInput
    class="object-picker-input-control"
    :value="inputValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :linked="linked && !!value && inputValue === value"
    :unmatched="unmatched"
    search-icon-only
    :aria-label="browseLabel"
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
