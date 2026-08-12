<script setup lang="ts">
import type { StandardSortableEntity } from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';

defineOptions({ name: 'RecordMetaSection' });

defineProps<{
  record: Partial<StandardSortableEntity>;
  showSortOrder?: boolean;
}>();
</script>

<template>
  <section class="record-meta">
    <h3>系统信息</h3>
    <dl>
      <div>
        <dt>ID</dt>
        <dd>{{ record.id ?? '-' }}</dd>
      </div>
      <div>
        <dt>版本</dt>
        <dd>{{ record.version ?? '-' }}</dd>
      </div>
      <div v-if="showSortOrder">
        <dt>排序号</dt>
        <dd>{{ record.sortOrder ?? '-' }}</dd>
      </div>
      <div>
        <dt>创建时间</dt>
        <dd><DateTimeText :value="record.createdAt" /></dd>
      </div>
      <div>
        <dt>更新时间</dt>
        <dd><DateTimeText :value="record.updatedAt" /></dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.record-meta {
  display: grid;
  gap: 10px;
  padding-top: 4px;
}

.record-meta h3 {
  margin: 0;
  color: var(--muyun-support-text);
  font-size: 14px;
}

dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 10px 16px;
  margin: 0;
}

dl div {
  min-width: 0;
}

dt {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}

dd {
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--muyun-support-text-body);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 900px) {
  dl {
    grid-template-columns: 1fr;
  }
}
</style>
