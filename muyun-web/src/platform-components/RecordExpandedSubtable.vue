<script setup lang="ts">
import { UiError, UiSpin } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecordExpandedSubtable' });

withDefaults(
  defineProps<{
    title: string;
    loading?: boolean;
    error?: string;
    loadingTip?: string;
    errorTitle?: string;
    emptyDescription?: string;
  }>(),
  {
    loading: false,
    error: undefined,
    loadingTip: '加载信息',
    errorTitle: '信息加载失败',
    emptyDescription: '暂无信息',
  },
);
</script>

<template>
  <section class="record-expanded-subtable">
    <header class="record-expanded-subtable-header">
      <h3>{{ title }}</h3>
      <div v-if="$slots.actions" class="record-expanded-subtable-actions"><slot name="actions" /></div>
    </header>
    <UiSpin v-if="loading" class="record-expanded-subtable-state" :tip="loadingTip" />
    <UiError v-else-if="error" :title="errorTitle" :message="error" />
    <p v-else-if="!$slots.default" class="record-expanded-subtable-empty">{{ emptyDescription }}</p>
    <slot v-else />
  </section>
</template>

<style scoped>
.record-expanded-subtable {
  display: grid;
  gap: 10px;
  padding: 12px 16px 14px 46px;
  border-top: 1px solid var(--muyun-border-subtle);
  background: var(--muyun-hover-subtle);
}
.record-expanded-subtable-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.record-expanded-subtable-header h3,
.record-expanded-subtable-empty {
  margin: 0;
}
.record-expanded-subtable-header h3 {
  color: var(--muyun-text);
  font-size: 13px;
  font-weight: 700;
}
.record-expanded-subtable-actions {
  display: inline-flex;
  gap: 4px;
}
.record-expanded-subtable-state {
  min-height: 56px;
}
.record-expanded-subtable-empty {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
</style>
