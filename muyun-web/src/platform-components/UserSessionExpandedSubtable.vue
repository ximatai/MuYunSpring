<script setup lang="ts">
import { UiButton } from '@muyun/vue-ui-antdv';
import type { UserSessionView } from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';
import RecordExpandedSubtable from './RecordExpandedSubtable.vue';
import {
  userSessionBrowserTitle,
  userSessionPresenceDescription,
  userSessionPresenceTitle,
  userSessionTerminalTitle,
} from './userSessionPresentation';

defineOptions({ name: 'UserSessionExpandedSubtable' });

const props = withDefaults(
  defineProps<{
    sessions: UserSessionView[];
    loading?: boolean;
    error?: string;
    actionsDisabled?: boolean;
    canRevoke?: (session: UserSessionView) => boolean;
    canRevokeAll?: boolean;
  }>(),
  {
    loading: false,
    error: undefined,
    actionsDisabled: false,
    canRevoke: () => false,
    canRevokeAll: false,
  },
);

const emit = defineEmits<{
  refresh: [];
  revoke: [session: UserSessionView];
  revokeAll: [];
}>();
</script>

<template>
  <RecordExpandedSubtable
    title="在线会话"
    :loading="loading"
    :error="error"
    loading-tip="加载在线会话"
    error-title="在线会话加载失败"
  >
    <template #actions>
      <UiButton type="text" icon-name="reload" :disabled="loading" @click="emit('refresh')">刷新</UiButton>
      <UiButton
        v-if="canRevokeAll"
        danger
        icon-name="power"
        :disabled="actionsDisabled || loading"
        @click="emit('revokeAll')"
      >
        全部下线
      </UiButton>
    </template>
    <p v-if="props.sessions.length === 0" class="user-session-expanded-empty">当前无在线会话</p>
    <div v-else class="user-session-expanded-list">
      <header class="user-session-expanded-header">
        <span>浏览器信息</span><span>登录与活动</span><span>操作</span>
      </header>
      <article v-for="session in props.sessions" :key="session.id" class="user-session-expanded-item">
        <div class="user-session-expanded-main">
          <strong :title="userSessionBrowserTitle(session)">{{ userSessionBrowserTitle(session) }}</strong>
          <span
            class="user-session-expanded-presence"
            :class="{ 'is-present': session.present, 'is-idle': session.presenceStatus === 'idle' }"
            :title="userSessionPresenceDescription(session)"
          >
            {{ userSessionPresenceTitle(session) }}
          </span>
          <span v-if="session.current" class="user-session-expanded-badge">当前会话</span>
        </div>
        <dl class="user-session-expanded-meta">
          <div>
            <dt>登录</dt>
            <dd><DateTimeText :value="session.issuedAt" /></dd>
          </div>
          <div>
            <dt>最近请求</dt>
            <dd><DateTimeText :value="session.lastSeenAt" /></dd>
          </div>
          <div>
            <dt>连接</dt>
            <dd :title="userSessionPresenceDescription(session)">{{ session.connectionCount ?? 0 }}</dd>
          </div>
          <div>
            <dt>IP</dt>
            <dd :title="session.loginIp || '-'">{{ session.loginIp || '-' }}</dd>
          </div>
          <div>
            <dt>终端</dt>
            <dd :title="userSessionTerminalTitle(session)">{{ userSessionTerminalTitle(session) }}</dd>
          </div>
        </dl>
        <UiButton
          danger
          icon-name="power"
          :disabled="actionsDisabled || !canRevoke(session)"
          @click="emit('revoke', session)"
        >
          下线
        </UiButton>
      </article>
    </div>
  </RecordExpandedSubtable>
</template>

<style scoped>
.user-session-expanded-empty {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.user-session-expanded-list {
  display: grid;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  background: var(--muyun-surface);
}
.user-session-expanded-header,
.user-session-expanded-item {
  display: grid;
  /*
   * Browser user agents are identity hints, not the primary use of the row.
   * Keep this column bounded so a wide host (such as system-account
   * management) does not turn it into a long unscannable line.
   */
  grid-template-columns: minmax(220px, 320px) minmax(0, 1fr) auto;
  gap: 10px;
  min-width: 0;
}
.user-session-expanded-header {
  padding: 8px 12px;
  border-bottom: 1px solid var(--muyun-border-subtle);
  color: var(--muyun-text-muted);
  font-size: 11px;
  font-weight: 600;
}
.user-session-expanded-item {
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid var(--muyun-border-subtle);
}
.user-session-expanded-item:last-child {
  border-bottom: 0;
}
.user-session-expanded-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.user-session-expanded-main strong {
  min-width: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-session-expanded-badge,
.user-session-expanded-presence {
  flex: none;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.user-session-expanded-presence.is-present {
  background: var(--muyun-positive-soft);
  color: var(--muyun-positive-soft-text);
}
.user-session-expanded-presence.is-idle {
  background: var(--muyun-warning-soft);
  color: var(--muyun-warning-soft-text);
}
.user-session-expanded-meta {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  min-width: 0;
  margin: 0;
}
.user-session-expanded-meta div {
  min-width: 0;
}
.user-session-expanded-meta dt {
  color: var(--muyun-text-muted);
  font-size: 11px;
  line-height: 1.2;
}
.user-session-expanded-meta dd {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 12px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-session-expanded-item > :deep(.ant-btn) {
  min-width: 64px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}
@media (max-width: 980px) {
  .user-session-expanded-header {
    display: none;
  }
  .user-session-expanded-item {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .user-session-expanded-meta {
    grid-column: 1 / -1;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 620px) {
  .user-session-expanded-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
