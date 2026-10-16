import { ref } from 'vue';
import {
  normalizeError,
  userFacingErrorMessage,
  platformLanguageContext,
  type LanguageContext,
  type AppError,
} from '@muyun/web-core';
import { presentPlatformError } from '@muyun/platform-components';
import type { QueryListRecord } from '@muyun/platform-components';
import type { UserSessionView, WebBusinessRealtimeEvent } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import { createRealtimeRefreshQueue } from '../pageRealtime';

export interface UserSessionState {
  records: UserSessionView[];
  loading: boolean;
  error?: string;
}

type StoredUserSessionState = UserSessionState & { failure?: AppError };

interface UserSessionRowsOptions {
  context: ModuleContext<QueryListRecord>;
  source: string;
  language?: LanguageContext;
}

/** IAM session state used by the standard list's business-owned row expansion. */
export function useUserSessionRows(options: UserSessionRowsOptions) {
  const language = options.language ?? platformLanguageContext;
  const sessionCollectionChanged = 'iam.user.session.collectionChanged';
  const expandedUserIds = ref<string[]>([]);
  const states = ref<Record<string, StoredUserSessionState>>({});
  const refreshQueue = createRealtimeRefreshQueue<string>({
    load: async (run) => {
      await Promise.all(run.keys.map((userId) => loadNow(userId, run.isLatest)));
    },
  });

  function loadUserSessions(userId: string | undefined) {
    refreshQueue.enqueue(userId);
  }

  async function loadNow(userId: string, isLatest: (userId: string) => boolean) {
    // Record actions are loaded immediately below. Before that request, `can`
    // is intentionally unknown rather than denied.
    if (options.context.can('sessions', userId) === false) return;
    setState(userId, { ...userSessionState(userId), loading: true, error: undefined, failure: undefined });
    try {
      await options.context.recordActions(userId);
      const records = await options.context.http.request<UserSessionView[]>({
        method: 'GET',
        path: `/iam.user/${encodeURIComponent(userId)}/sessions`,
      });
      if (isLatest(userId)) setState(userId, { records, loading: false });
    } catch (cause) {
      if (!isLatest(userId)) return;
      const failure = normalizeError(cause);
      presentPlatformError(failure, { language, source: `${options.source}-sessions`, phase: 'load' });
      setState(userId, { ...userSessionState(userId), loading: false, failure });
    }
  }

  function handleUserRowExpand(record: { id?: string }, expanded: boolean) {
    const userId = String(record.id ?? '');
    if (!userId) return;
    expandedUserIds.value = expanded
      ? Array.from(new Set([...expandedUserIds.value, userId]))
      : expandedUserIds.value.filter((id) => id !== userId);
    if (expanded && userSessionState(userId).records.length === 0) loadUserSessions(userId);
  }

  function handleUserSessionBusinessEvent(event: WebBusinessRealtimeEvent) {
    if (event.type !== sessionCollectionChanged || event.moduleAlias !== 'iam.user') return;
    const userId = String(event.recordId ?? '');
    if (userId && expandedUserIds.value.includes(userId)) loadUserSessions(userId);
  }

  function userSessionState(userId: string | undefined): UserSessionState {
    const state = userId ? states.value[userId] : undefined;
    if (!state) return { records: [], loading: false };
    return state.failure ? { ...state, error: userFacingErrorMessage(state.failure, language) } : state;
  }

  function reset() {
    refreshQueue.reset();
    expandedUserIds.value = [];
    states.value = {};
  }

  function setState(userId: string, state: StoredUserSessionState) {
    states.value = { ...states.value, [userId]: state };
  }

  return { handleUserRowExpand, handleUserSessionBusinessEvent, loadUserSessions, reset, userSessionState };
}
