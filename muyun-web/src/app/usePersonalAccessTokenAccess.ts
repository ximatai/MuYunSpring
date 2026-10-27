import { onMounted, onScopeDispose, readonly, ref, watch } from 'vue';
import type { PersonalAccessTokenClient } from '@muyun/web-core';

/** The host owns credential management availability; the workbench only renders approved commands. */
export function usePersonalAccessTokenAccess(options: {
  client: PersonalAccessTokenClient;
  sessionToken: () => string | undefined;
  scopeKey?: () => unknown;
}) {
  const available = ref(false);
  const sessionToken = ref<string>();
  let revision = 0;
  let disposed = false;
  async function refresh() {
    if (disposed) return;
    const session = options.sessionToken();
    const scope = options.scopeKey?.();
    const run = ++revision;
    if (sessionToken.value !== session) {
      available.value = false;
      sessionToken.value = session;
    }
    if (!session) {
      available.value = false;
      return;
    }
    const active = () =>
      !disposed &&
      run === revision &&
      sessionToken.value === session &&
      options.sessionToken() === session &&
      options.scopeKey?.() === scope;
    try {
      const result = await options.client.capabilities(session);
      if (active()) available.value = result.canCreate === true;
    } catch {
      if (active()) available.value = false;
    }
  }
  watch(
    [options.sessionToken, () => options.scopeKey?.()],
    () => {
      available.value = false;
      void refresh();
    },
    { immediate: true, flush: 'sync' },
  );
  const onFocus = () => {
    void refresh();
  };
  const onStorage = (event: StorageEvent) => {
    if (event.storageArea === window.localStorage && options.sessionToken() !== sessionToken.value) {
      void refresh();
    }
  };
  onMounted(() => {
    window.addEventListener('focus', onFocus);
    window.addEventListener('storage', onStorage);
  });
  onScopeDispose(() => {
    disposed = true;
    revision++;
    window.removeEventListener('focus', onFocus);
    window.removeEventListener('storage', onStorage);
  });
  return { available, sessionToken: readonly(sessionToken), refresh };
}
