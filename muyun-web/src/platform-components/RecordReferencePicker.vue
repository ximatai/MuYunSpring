<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue';
import { normalizeError, useLanguageContext, userFacingErrorMessage } from '@muyun/web-core';
import { UiError, UiSelect } from '@muyun/vue-ui-antdv';
import ReferencePicker from './ReferencePicker.vue';
import type { ReferencePickerCandidate, ReferencePickerProvider } from './referencePickerModel';
import {
  createRecordReferencePickerProvider,
  type RecordReferencePickerSource,
  type RecordReferenceCandidate,
} from './recordReferencePickerProvider';
import type { RecordPickerRecord } from './recordPickerConstraints';

const props = withDefaults(
  defineProps<
    RecordReferencePickerSource & {
      value?: string | string[];
      multiple?: boolean;
      reloadKey?: string | number;
      placeholder?: string;
      invalid?: boolean;
      disabled?: boolean;
      allowClear?: boolean;
    }
  >(),
  { allowClear: true },
);
const emit = defineEmits<{
  'update:value': [value: string | string[] | undefined];
  select: [records: RecordPickerRecord[]];
  'selection-resolved': [records: RecordPickerRecord[]];
}>();
const language = useLanguageContext();
const provider = shallowRef<ReferencePickerProvider>();
const error = shallowRef<unknown>();
let revision = 0;
watch(
  () =>
    [
      props.context,
      props.mode,
      props.loadOptions,
      props.loadPage,
      props.loadTree,
      props.resolveOptions,
      props.reloadKey,
    ] as const,
  async () => {
    const request = ++revision;
    provider.value = undefined;
    error.value = undefined;
    try {
      const next = await createRecordReferencePickerProvider(
        {
          ...props,
          // Presentation and constraints may be inline functions recreated by a host render.
          // Keep them reactive without treating a render as a new authorization/source scope.
          get constraints() {
            return props.constraints;
          },
          get titleOf() {
            return props.titleOf;
          },
          get descriptionOf() {
            return props.descriptionOf;
          },
          get filterOption() {
            return props.filterOption;
          },
        },
        language,
      );
      if (request === revision) provider.value = next;
    } catch (cause) {
      if (request === revision) error.value = cause;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  revision += 1;
});
function recordsOf(candidates: ReferencePickerCandidate[]) {
  return candidates.flatMap((candidate) => {
    const record = (candidate as Partial<RecordReferenceCandidate>).record;
    return record ? [record] : [];
  });
}
</script>

<template>
  <ReferencePicker
    v-if="provider"
    :provider="provider"
    :value="value"
    :multiple="multiple"
    mode="dropdown"
    :page-size="50"
    :placeholder="placeholder"
    :invalid="invalid"
    :disabled="disabled"
    :allow-clear="allowClear"
    @update:value="emit('update:value', $event)"
    @select="emit('select', recordsOf($event))"
    @selection-resolved="emit('selection-resolved', recordsOf($event))"
  />
  <UiSelect
    v-else
    :options="[]"
    :value="value"
    :mode="multiple ? 'multiple' : undefined"
    :placeholder="placeholder"
    disabled
    :loading="!error"
  />
  <UiError v-if="error" :message="userFacingErrorMessage(normalizeError(error), language)" />
</template>
