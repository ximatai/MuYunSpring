import { onScopeDispose, ref } from 'vue';
import { useModuleHttpClient, type HttpClient } from '@muyun/web-core';
import type { UserSessionStatusView, WebBusinessRealtimeEvent } from '@muyun/web-contracts';
import { presentPlatformError } from '@muyun/platform-components';
import { subscribeAppBusinessEvents } from '../realtime';

const sessionCollectionChanged = 'iam.user.session.collectionChanged';
const stores = new WeakMap<HttpClient, ReturnType<typeof createStatusStore>>();

/** IAM session facts are batched within the owning page's immutable transport. */
export function useUserOnlineStatusStore() {
  const http = useModuleHttpClient();
  let store = stores.get(http);
  if (!store) {
    store = createStatusStore(http);
    stores.set(http, store);
  }
  const current = store;
  current.users += 1;
  onScopeDispose(() => {
    if (--current.users === 0) {
      current.dispose();
      stores.delete(http);
    }
  });
  return { observe: current.observe, statusOf: current.statusOf, refresh: current.refresh };
}

function createStatusStore(http: HttpClient) {
  const statuses = ref<Record<string, UserSessionStatusView>>({});
  const pendingUserIds = new Set<string>();
  let scheduled = false;
  let disposed = false;
  const subscription = subscribeAppBusinessEvents((event) => {
    if (isUserSessionChange(event)) refresh(event.recordId);
  });

  function observe(userId: string | undefined) {
    if (!userId || statuses.value[userId]) return;
    refresh(userId);
  }

  function statusOf(userId: string | undefined) {
    return userId ? statuses.value[userId] : undefined;
  }

  function refresh(userId: string | undefined) {
    if (!userId || disposed) return;
    pendingUserIds.add(userId);
    if (scheduled) return;
    scheduled = true;
    queueMicrotask(() => {
      scheduled = false;
      if (!disposed) void loadPending();
    });
  }

  async function loadPending() {
    const userIds = Array.from(pendingUserIds);
    pendingUserIds.clear();
    if (userIds.length === 0) return;
    try {
      const loaded = await http.request<UserSessionStatusView[]>({
        method: 'POST',
        path: '/iam.user/sessions/status',
        body: { userIds },
      });
      if (disposed) return;
      statuses.value = {
        ...statuses.value,
        ...Object.fromEntries(loaded.map((status) => [status.userId, status])),
      };
    } catch (cause) {
      if (!disposed) presentPlatformError(cause, { source: 'iam-user-online-status', phase: 'load' });
    }
  }

  return {
    users: 0,
    observe,
    statusOf,
    refresh,
    dispose() {
      disposed = true;
      pendingUserIds.clear();
      subscription.unsubscribe();
    },
  };
}

function isUserSessionChange(event: WebBusinessRealtimeEvent) {
  return (
    event.type === sessionCollectionChanged && event.moduleAlias === 'iam.user' && Boolean(event.recordId)
  );
}
