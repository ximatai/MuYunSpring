import { ref } from 'vue';
import type { UserSessionStatusView, WebBusinessRealtimeEvent } from '@muyun/web-contracts';
import { presentPlatformError } from '@muyun/platform-components';
import { createBackendHttpClient } from '../backendHttp';
import { subscribeAppBusinessEvents } from '../realtime';

const sessionCollectionChanged = 'iam.user.session.collectionChanged';
const statuses = ref<Record<string, UserSessionStatusView>>({});
const pendingUserIds = new Set<string>();
let scheduled = false;
let realtimeSubscribed = false;

/**
 * IAM-owned batch status cache for the standard user list. It deliberately
 * remains outside the generic module runner: "online" is a user-session fact,
 * not a platform record field.
 */
export function useUserOnlineStatusStore() {
  ensureRealtimeSubscription();

  function observe(userId: string | undefined) {
    if (!userId || statuses.value[userId]) return;
    pendingUserIds.add(userId);
    scheduleLoad();
  }

  function statusOf(userId: string | undefined) {
    return userId ? statuses.value[userId] : undefined;
  }

  function refresh(userId: string | undefined) {
    if (!userId) return;
    pendingUserIds.add(userId);
    scheduleLoad();
  }

  return { observe, statusOf, refresh };
}

function ensureRealtimeSubscription() {
  if (realtimeSubscribed) return;
  realtimeSubscribed = true;
  subscribeAppBusinessEvents((event) => {
    if (isUserSessionChange(event)) {
      pendingUserIds.add(event.recordId);
      scheduleLoad();
    }
  });
}

function scheduleLoad() {
  if (scheduled) return;
  scheduled = true;
  queueMicrotask(() => {
    scheduled = false;
    void loadPending();
  });
}

async function loadPending() {
  const userIds = Array.from(pendingUserIds);
  pendingUserIds.clear();
  if (userIds.length === 0) return;
  try {
    const loaded = await createBackendHttpClient().request<UserSessionStatusView[]>({
      method: 'POST',
      path: '/iam.user/sessions/status',
      body: { userIds },
    });
    statuses.value = {
      ...statuses.value,
      ...Object.fromEntries(loaded.map((status) => [status.userId, status])),
    };
  } catch (cause) {
    presentPlatformError(cause, { source: 'iam-user-online-status', phase: 'load' });
  }
}

function isUserSessionChange(event: WebBusinessRealtimeEvent) {
  return (
    event.type === sessionCollectionChanged && event.moduleAlias === 'iam.user' && Boolean(event.recordId)
  );
}
