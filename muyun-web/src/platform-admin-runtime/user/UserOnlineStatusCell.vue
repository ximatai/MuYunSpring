<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import type { QueryListRecord, RecordQueryListColumn } from '@muyun/platform-components';
import { useUserOnlineStatusStore } from './userOnlineStatusStore';

defineOptions({ name: 'UserOnlineStatusCell' });

const props = defineProps<{ record: QueryListRecord; column?: RecordQueryListColumn }>();
const statusStore = useUserOnlineStatusStore();
const userId = computed(() => (props.record.id == null ? undefined : String(props.record.id)));
const status = computed(() => statusStore.statusOf(userId.value));
const title = computed(() => {
  const value = status.value;
  if (!value) return '-';
  if (value.present) {
    const presentCount = value.presentSessionCount ?? 0;
    const idleCount = value.idleSessionCount ?? 0;
    return idleCount >= presentCount
      ? `闲置 (${presentCount}/${value.activeSessionCount})`
      : `使用中 (${Math.max(0, presentCount - idleCount)}/${value.activeSessionCount})`;
  }
  return value.online ? `在线 (${value.activeSessionCount})` : '离线';
});

function observe() {
  statusStore.observe(userId.value);
}

onMounted(observe);
watch(userId, observe);
</script>

<template>
  <span
    :class="{
      'user-online-status--present': status?.present,
      'user-online-status--offline': status && !status.online,
    }"
  >
    {{ title }}
  </span>
</template>

<style scoped>
.user-online-status--present {
  color: var(--muyun-positive-soft-text);
}
.user-online-status--offline {
  color: var(--muyun-text-muted);
}
</style>
