<script setup lang="ts">
import { computed } from 'vue';
import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';
import { readonlyReferenceDisplayItems } from './readonlyReferenceDisplay';
import { useReferenceRecordDetailBrowser } from './referenceRecordDetailBrowser';

defineOptions({ name: 'ReadonlyReferenceValue' });

const props = defineProps<{
  reference: Pick<ResolvedReferenceFieldDescriptor, 'cardinality' | 'targetModuleAlias'>;
  value: unknown;
  summary: unknown;
  /** Detail surfaces retain their own empty-value convention; lists intentionally omit it. */
  emptyText?: string;
}>();

const browser = useReferenceRecordDetailBrowser();
const items = computed(() => {
  // Depend on target authorization without requesting it for every projected field.
  void browser?.revision.value;
  return readonlyReferenceDisplayItems(props.reference, props.value, props.summary).map((item) => ({
    ...item,
    browseable: item.browseable && (browser?.canBrowse(props.reference.targetModuleAlias, item.id) ?? false),
  }));
});

function open(id: string) {
  browser?.open(props.reference.targetModuleAlias, id);
}
</script>

<template>
  <span v-if="items.length === 0">{{ emptyText ?? '' }}</span>
  <template v-for="(item, index) in items" :key="item.id">
    <span v-if="index > 0" class="readonly-reference-value__separator">、</span>
    <button
      v-if="item.browseable"
      type="button"
      class="readonly-reference-value__link"
      :title="`查看 ${item.label}`"
      @click.stop="open(item.id)"
    >
      {{ item.label }}
    </button>
    <span v-else>{{ item.label }}</span>
  </template>
</template>

<style scoped>
.readonly-reference-value__link {
  padding: 0;
  color: var(--muyun-primary);
  font: inherit;
  text-align: inherit;
  text-decoration: none;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.readonly-reference-value__link:hover,
.readonly-reference-value__link:focus-visible {
  text-decoration: underline;
}
</style>
