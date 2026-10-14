<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, onMounted, watch } from 'vue';
import type { QueryListRecord, RecordQueryListColumn } from '@muyun/platform-components';
import { useUserOnlineStatusStore } from './userOnlineStatusStore';

const language = useLanguageContext();

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
      ? language.t('platform.screen.UserOnlineStatusCell.idle-value-value', '闲置 ({value1}/{value2})', {
          value1: presentCount,
          value2: value.activeSessionCount,
        })
      : language.t('platform.screen.UserOnlineStatusCell.active-value-value', '使用中 ({value1}/{value2})', {
          value1: Math.max(0, presentCount - idleCount),
          value2: value.activeSessionCount,
        });
  }
  return value.online
    ? language.t('platform.screen.UserOnlineStatusCell.online-value', '在线 ({value1})', {
        value1: value.activeSessionCount,
      })
    : language.t('platform.sessions.presence.offline', '离线');
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
