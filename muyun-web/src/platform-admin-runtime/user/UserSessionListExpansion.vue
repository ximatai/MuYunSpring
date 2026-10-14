<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';

import { computed, onUnmounted, watch } from 'vue';
import { UserSessionExpandedSubtable } from '@muyun/platform-components';
import { confirmAction } from '@muyun/vue-ui-antdv';
import type { ModulePageListRowExpansionContext } from '@muyun/dynamic-page-runtime';
import type { UserSessionView } from '@muyun/web-contracts';
import { subscribeAppBusinessEvents } from '../realtime';
import { useUserSessionRows } from './useUserSessionRows';

const language = useLanguageContext();

defineOptions({ name: 'UserSessionListExpansion' });

const props = defineProps<{ context: ModulePageListRowExpansionContext }>();
const userId = computed(() =>
  props.context.record.id == null ? undefined : String(props.context.record.id),
);
const sessionRows = useUserSessionRows({
  context: props.context.module,
  source: 'iam-user-list-expansion',
  language,
});
const state = computed(() => sessionRows.userSessionState(userId.value));
const revokableSessions = computed(() =>
  state.value.records.filter(
    (session) => !session.current && props.context.module.can('revokeSession', userId.value) === true,
  ),
);
const canRevokeAll = computed(
  () =>
    revokableSessions.value.length > 1 && props.context.module.can('revokeSessions', userId.value) === true,
);

watch(
  [userId, () => props.context.expanded],
  ([id, expanded]) => {
    if (!id) return;
    sessionRows.handleUserRowExpand({ id }, expanded);
  },
  { immediate: true },
);

const businessEvents = subscribeAppBusinessEvents((event) =>
  sessionRows.handleUserSessionBusinessEvent(event),
);
onUnmounted(() => {
  businessEvents.unsubscribe();
  sessionRows.reset();
});

async function revoke(session: UserSessionView) {
  const id = userId.value;
  if (!id || session.current || props.context.module.can('revokeSession', id) !== true) return;
  const confirmed = await confirmAction(
    {
      title: language.t('platform.screen.UserSessionListExpansion.sign-out-session', '下线登录会话'),
      content: language.t(
        'platform.screen.UserSessionListExpansion.sign-out-this-login-session',
        '确认下线该登录会话？',
      ),
      okText: language.t('platform.screen.UserSessionExpandedSubtable.sign-out-session', '下线'),
      danger: true,
    },
    language,
  );
  if (!confirmed) return;
  await props.context.module.http.request({
    method: 'POST',
    path: `/iam.user/${encodeURIComponent(id)}/sessions/${encodeURIComponent(session.id)}/revoke`,
  });
  sessionRows.loadUserSessions(id);
}

async function revokeAll() {
  const id = userId.value;
  const sessionIds = revokableSessions.value.map((session) => session.id);
  if (!id || sessionIds.length === 0 || props.context.module.can('revokeSessions', id) !== true) return;
  const confirmed = await confirmAction(
    {
      title: language.t('platform.screen.UserSessionExpandedSubtable.sign-out-all-sessions', '全部下线'),
      content: language.t(
        'platform.screen.UserSessionListExpansion.sign-out-this-user-s-value-login-sessions',
        '确认下线该用户的 {value1} 个登录会话？',
        { value1: sessionIds.length },
      ),
      okText: language.t('platform.screen.UserSessionExpandedSubtable.sign-out-all-sessions', '全部下线'),
      danger: true,
    },
    language,
  );
  if (!confirmed) return;
  await props.context.module.http.request({
    method: 'POST',
    path: `/iam.user/${encodeURIComponent(id)}/sessions/revoke`,
    body: { sessionIds },
  });
  sessionRows.loadUserSessions(id);
}
</script>

<template>
  <UserSessionExpandedSubtable
    embedded
    :sessions="state.records"
    :loading="state.loading"
    :error="state.error"
    :can-revoke="(session) => !session.current && context.module.can('revokeSession', userId) === true"
    :can-revoke-all="canRevokeAll"
    @refresh="sessionRows.loadUserSessions(userId)"
    @revoke="revoke"
    @revoke-all="revokeAll"
  />
</template>
