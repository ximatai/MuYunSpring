<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext, presentationLabel } from '@muyun/web-core';
import type { WebSort, QuerySchemaField } from '@muyun/web-contracts';
import { UiButton, UiDropdown } from '@muyun/vue-ui-antdv';

const props = defineProps<{
  fields: Array<Pick<QuerySchemaField, 'name' | 'title' | 'titleText'>>;
  sorts?: WebSort[];
  disabled?: boolean;
}>();
const emit = defineEmits<{ change: [sorts: WebSort[] | undefined] }>();
const language = useLanguageContext();
const items = computed(() => [
  { key: 'default', title: language.t('platform.content.sort-default', '默认顺序') },
  ...props.fields.flatMap((field) =>
    [false, true].map((desc) => ({
      key: `${field.name}:${desc ? 'desc' : 'asc'}`,
      title: language.t(
        desc ? 'platform.content.sort-desc' : 'platform.content.sort-asc',
        desc ? '{field}降序' : '{field}升序',
        { field: presentationLabel(field.title ?? field.name, field.titleText, language) },
      ),
    })),
  ),
]);
const selected = computed(() =>
  props.sorts === undefined
    ? 'default'
    : props.sorts.length === 1
      ? `${props.sorts[0]!.field}:${props.sorts[0]!.desc ? 'desc' : 'asc'}`
      : undefined,
);
const currentTitle = computed(() =>
  selected.value && selected.value !== 'default'
    ? items.value.find((item) => item.key === selected.value)?.title
    : undefined,
);
function select(key: string) {
  if (props.disabled) return;
  if (key === 'default') {
    emit('change', undefined);
    return;
  }
  for (const field of props.fields)
    for (const desc of [false, true])
      if (key === `${field.name}:${desc ? 'desc' : 'asc'}`) emit('change', [{ field: field.name, desc }]);
}
</script>

<template>
  <UiDropdown v-if="fields.length > 0" :items="items" :selected-key="selected" @select="select">
    <UiButton :disabled="disabled" icon-name="down">{{
      currentTitle ?? language.t('platform.content.sort-by', '排序方式')
    }}</UiButton>
  </UiDropdown>
</template>
